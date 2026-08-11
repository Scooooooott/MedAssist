package com.medassist.agent.security;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public final class StructuredQueryGuard {
  private static final int DEFAULT_MAX_LIMIT = 100;
  private static final int MAX_SQL_LENGTH = 16_384;
  private static final Set<String> WRITE_KEYWORDS =
      Set.of(
          "INSERT",
          "UPDATE",
          "DELETE",
          "MERGE",
          "UPSERT",
          "DROP",
          "ALTER",
          "CREATE",
          "TRUNCATE",
          "GRANT",
          "REVOKE",
          "COPY",
          "VACUUM",
          "ANALYZE",
          "REFRESH",
          "REINDEX",
          "CALL",
          "DO",
          "EXECUTE",
          "SET",
          "RESET");
  private static final Set<String> UNSAFE_KEYWORDS =
      Set.of("UNION", "INTERSECT", "EXCEPT", "FOR", "INTO", "RETURNING");
  private static final Set<String> DANGEROUS_FUNCTIONS =
      Set.of(
          "PG_SLEEP",
          "PG_READ_FILE",
          "PG_LS_DIR",
          "LO_IMPORT",
          "LO_EXPORT",
          "DBLINK_CONNECT",
          "DBLINK_EXEC",
          "SET_CONFIG",
          "CURRENT_SETTING",
          "QUERY_TO_XML",
          "VERSION",
          "CURRENT_DATABASE",
          "CURRENT_SCHEMA",
          "INET_SERVER_ADDR",
          "INET_CLIENT_ADDR",
          "HAS_TABLE_PRIVILEGE",
          "HAS_SCHEMA_PRIVILEGE",
          "PG_HAS_ROLE",
          "PG_STAT_FILE",
          "PG_READ_BINARY_FILE",
          "PG_TERMINATE_BACKEND",
          "PG_CANCEL_BACKEND",
          "PG_ADVISORY_LOCK",
          "PG_TRY_ADVISORY_LOCK",
          "PG_BACKEND_PID",
          "CURRENT_USER",
          "SESSION_USER",
          "CURRENT_ROLE");
  private static final Set<String> CLAUSE_KEYWORDS =
      Set.of(
          "WHERE",
          "GROUP",
          "ORDER",
          "HAVING",
          "LIMIT",
          "OFFSET",
          "FETCH",
          "FOR",
          "UNION",
          "EXCEPT",
          "INTERSECT",
          "JOIN",
          "LEFT",
          "RIGHT",
          "FULL",
          "INNER",
          "OUTER",
          "CROSS",
          "ON",
          "USING",
          "WINDOW");
  private final Set<String> allowedRelations;
  private final int maxLimit;

  public StructuredQueryGuard() {
    this(Set.of(), DEFAULT_MAX_LIMIT);
  }

  public StructuredQueryGuard(final Set<String> allowedRelations) {
    this(allowedRelations, DEFAULT_MAX_LIMIT);
  }

  public StructuredQueryGuard(final Set<String> allowedRelations, final int maxLimit) {
    Objects.requireNonNull(allowedRelations, "allowedRelations");
    if (maxLimit <= 0) {
      throw new IllegalArgumentException("maxLimit must be positive");
    }
    final Set<String> normalized = new HashSet<>();
    for (final String relation : allowedRelations) {
      if (relation == null || relation.isBlank()) {
        throw new IllegalArgumentException("allowed relations cannot be blank");
      }
      normalized.add(normalizeRelation(relation));
    }
    this.allowedRelations = Set.copyOf(normalized);
    this.maxLimit = maxLimit;
  }

  public SqlValidationResult validate(final String sql) {
    final String sqlHash = Hashing.sha256(sql);
    if (sql == null || sql.isBlank() || sql.length() > MAX_SQL_LENGTH) {
      return reject(SqlViolation.INVALID_INPUT, sqlHash);
    }
    final String leadingWord = leadingWord(sql);
    if (!leadingWord.isEmpty() && !leadingWord.equals("SELECT")) {
      return WRITE_KEYWORDS.contains(leadingWord)
          ? reject(SqlViolation.WRITE_OPERATION, sqlHash)
          : reject(SqlViolation.NOT_SELECT, sqlHash);
    }

    final List<SqlToken> tokens;
    try {
      tokens = tokenize(sql);
    } catch (final SqlGuardException exception) {
      return reject(exception.violation, sqlHash);
    }
    if (tokens.isEmpty()) {
      return reject(SqlViolation.INVALID_INPUT, sqlHash);
    }
    if (count(tokens, ";") > 1 || (contains(tokens, ";") && !lastIs(tokens, ";"))) {
      return reject(SqlViolation.MULTI_STATEMENT, sqlHash);
    }
    final int statementEnd = lastIs(tokens, ";") ? tokens.size() - 1 : tokens.size();
    if (!isKeyword(tokens.get(0), "SELECT")) {
      return WRITE_KEYWORDS.contains(tokens.get(0).upperText())
          ? reject(SqlViolation.WRITE_OPERATION, sqlHash)
          : reject(SqlViolation.NOT_SELECT, sqlHash);
    }
    for (int index = 1; index < statementEnd; index++) {
      final SqlToken token = tokens.get(index);
      if (isKeyword(token, "SELECT")) {
        return reject(SqlViolation.SUBQUERY_NOT_ALLOWED, sqlHash);
      }
      if (isKeyword(token, "WITH")) {
        return reject(SqlViolation.SUBQUERY_NOT_ALLOWED, sqlHash);
      }
      if (isKeyword(token, "LIMIT") && index + 1 >= statementEnd) {
        return reject(SqlViolation.INVALID_LIMIT, sqlHash);
      }
      if (isKeyword(token, "LIMIT") && index + 1 < statementEnd) {
        final SqlToken limitValue = tokens.get(index + 1);
        if (limitValue.kind != TokenKind.NUMBER || !limitValue.text.matches("\\d+")) {
          return reject(SqlViolation.INVALID_LIMIT, sqlHash);
        }
        try {
          final int value = Integer.parseInt(limitValue.text);
          if (value <= 0) {
            return reject(SqlViolation.INVALID_LIMIT, sqlHash);
          }
          if (value > maxLimit) {
            return reject(SqlViolation.LIMIT_TOO_LARGE, sqlHash);
          }
        } catch (final NumberFormatException exception) {
          return reject(SqlViolation.LIMIT_TOO_LARGE, sqlHash);
        }
      }
      if (token.kind == TokenKind.WORD) {
        if (WRITE_KEYWORDS.contains(token.upperText())) {
          return reject(SqlViolation.WRITE_OPERATION, sqlHash);
        }
        if (UNSAFE_KEYWORDS.contains(token.upperText())) {
          return reject(SqlViolation.UNSAFE_KEYWORD, sqlHash);
        }
        if (DANGEROUS_FUNCTIONS.contains(token.upperText())
            && index + 1 < statementEnd
            && isSymbol(tokens.get(index + 1), "(")) {
          return reject(SqlViolation.DANGEROUS_FUNCTION, sqlHash);
        }
      }
    }

    final SqlViolation relationViolation = validateRelations(tokens, statementEnd);
    if (relationViolation != SqlViolation.NONE) {
      return reject(relationViolation, sqlHash);
    }
    if (countKeyword(tokens, statementEnd, "LIMIT") != 1) {
      return reject(SqlViolation.MISSING_LIMIT, sqlHash);
    }
    return new SqlValidationResult(true, SqlViolation.NONE, sqlHash);
  }

  private SqlViolation validateRelations(final List<SqlToken> tokens, final int statementEnd) {
    for (int index = 0; index < statementEnd; index++) {
      if (isKeyword(tokens.get(index), "FROM")) {
        final RelationCheck first = relationAt(tokens, index + 1, statementEnd);
        if (first.violation != SqlViolation.NONE) {
          return first.violation;
        }
        int cursor = first.nextIndex;
        while (cursor < statementEnd) {
          if (isKeyword(tokens.get(cursor), "AS")) {
            if (cursor + 1 >= statementEnd || !isRelationToken(tokens.get(cursor + 1))) {
              return SqlViolation.MALFORMED_SQL;
            }
            cursor += 2;
          } else if (isAliasToken(tokens.get(cursor))) {
            cursor++;
          }
          if (cursor < statementEnd && isSymbol(tokens.get(cursor), ",")) {
            final RelationCheck next = relationAt(tokens, cursor + 1, statementEnd);
            if (next.violation != SqlViolation.NONE) {
              return next.violation;
            }
            cursor = next.nextIndex;
            continue;
          }
          break;
        }
      } else if (isKeyword(tokens.get(index), "JOIN")) {
        final RelationCheck join = relationAt(tokens, index + 1, statementEnd);
        if (join.violation != SqlViolation.NONE) {
          return join.violation;
        }
      }
    }
    return SqlViolation.NONE;
  }

  private RelationCheck relationAt(
      final List<SqlToken> tokens, final int start, final int statementEnd) {
    int index = start;
    if (index >= statementEnd) {
      return new RelationCheck(start, SqlViolation.MALFORMED_SQL);
    }
    if (isKeyword(tokens.get(index), "ONLY") || isKeyword(tokens.get(index), "LATERAL")) {
      if (isKeyword(tokens.get(index), "LATERAL")) {
        return new RelationCheck(start, SqlViolation.SUBQUERY_NOT_ALLOWED);
      }
      index++;
    }
    if (index >= statementEnd || isSymbol(tokens.get(index), "(")) {
      return new RelationCheck(start, SqlViolation.SUBQUERY_NOT_ALLOWED);
    }
    if (!isRelationToken(tokens.get(index))) {
      return new RelationCheck(start, SqlViolation.MALFORMED_SQL);
    }
    final StringBuilder relation = new StringBuilder(tokens.get(index).text);
    index++;
    if (index + 1 < statementEnd && isSymbol(tokens.get(index), ".")) {
      if (!isRelationToken(tokens.get(index + 1))) {
        return new RelationCheck(start, SqlViolation.MALFORMED_SQL);
      }
      relation.append('.').append(tokens.get(index + 1).text);
      index += 2;
    }
    if (!allowedRelations.contains(normalizeRelation(relation.toString()))) {
      return new RelationCheck(start, SqlViolation.NON_WHITELISTED_RELATION);
    }
    return new RelationCheck(index, SqlViolation.NONE);
  }

  private static List<SqlToken> tokenize(final String sql) {
    final List<SqlToken> tokens = new ArrayList<>();
    int index = 0;
    while (index < sql.length()) {
      final char current = sql.charAt(index);
      if (Character.isWhitespace(current)) {
        index++;
        continue;
      }
      if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
        throw new SqlGuardException(SqlViolation.COMMENT);
      }
      if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
        throw new SqlGuardException(SqlViolation.COMMENT);
      }
      if (current == '#') {
        throw new SqlGuardException(SqlViolation.COMMENT);
      }
      if (current == '\'' || current == '"') {
        final char quote = current;
        final boolean quotedIdentifier = quote == '"';
        final StringBuilder value = new StringBuilder();
        index++;
        boolean closed = false;
        while (index < sql.length()) {
          final char character = sql.charAt(index++);
          if (character == quote) {
            if (index < sql.length() && sql.charAt(index) == quote) {
              value.append(quote);
              index++;
            } else {
              closed = true;
              break;
            }
          } else {
            value.append(character);
          }
        }
        if (!closed) {
          throw new SqlGuardException(SqlViolation.MALFORMED_SQL);
        }
        tokens.add(
            new SqlToken(value.toString(), quotedIdentifier ? TokenKind.QUOTED : TokenKind.STRING));
        continue;
      }
      if (Character.isLetter(current) || current == '_' || current == '$') {
        final int start = index++;
        while (index < sql.length()) {
          final char character = sql.charAt(index);
          if (!(Character.isLetterOrDigit(character) || character == '_' || character == '$')) {
            break;
          }
          index++;
        }
        tokens.add(new SqlToken(sql.substring(start, index), TokenKind.WORD));
        continue;
      }
      if (Character.isDigit(current)) {
        final int start = index++;
        while (index < sql.length() && Character.isDigit(sql.charAt(index))) {
          index++;
        }
        tokens.add(new SqlToken(sql.substring(start, index), TokenKind.NUMBER));
        continue;
      }
      if (",;.()=*<>+-/%".indexOf(current) >= 0) {
        tokens.add(new SqlToken(String.valueOf(current), TokenKind.SYMBOL));
        index++;
        continue;
      }
      throw new SqlGuardException(SqlViolation.MALFORMED_SQL);
    }
    return tokens;
  }

  private static boolean isRelationToken(final SqlToken token) {
    return token.kind == TokenKind.WORD || token.kind == TokenKind.QUOTED;
  }

  private static boolean isAliasToken(final SqlToken token) {
    return isRelationToken(token) && !CLAUSE_KEYWORDS.contains(token.upperText());
  }

  private static boolean isKeyword(final SqlToken token, final String keyword) {
    return token.kind == TokenKind.WORD && token.upperText().equals(keyword);
  }

  private static boolean isSymbol(final SqlToken token, final String symbol) {
    return token.kind == TokenKind.SYMBOL && token.text.equals(symbol);
  }

  private static int count(final List<SqlToken> tokens, final String symbol) {
    int count = 0;
    for (final SqlToken token : tokens) {
      if (isSymbol(token, symbol)) {
        count++;
      }
    }
    return count;
  }

  private static boolean contains(final List<SqlToken> tokens, final String symbol) {
    return count(tokens, symbol) > 0;
  }

  private static boolean lastIs(final List<SqlToken> tokens, final String symbol) {
    return !tokens.isEmpty() && isSymbol(tokens.get(tokens.size() - 1), symbol);
  }

  private static int countKeyword(
      final List<SqlToken> tokens, final int statementEnd, final String keyword) {
    int count = 0;
    for (int index = 0; index < statementEnd; index++) {
      if (isKeyword(tokens.get(index), keyword)) {
        count++;
      }
    }
    return count;
  }

  private static String normalizeRelation(final String relation) {
    return relation.trim().toLowerCase(Locale.ROOT);
  }

  private static String leadingWord(final String sql) {
    int index = 0;
    while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) {
      index++;
    }
    final int start = index;
    while (index < sql.length()
        && (Character.isLetter(sql.charAt(index)) || sql.charAt(index) == '_')) {
      index++;
    }
    return sql.substring(start, index).toUpperCase(Locale.ROOT);
  }

  private static SqlValidationResult reject(final SqlViolation violation, final String sqlHash) {
    return new SqlValidationResult(false, violation, sqlHash);
  }

  private enum TokenKind {
    WORD,
    NUMBER,
    STRING,
    QUOTED,
    SYMBOL
  }

  private record SqlToken(String text, TokenKind kind) {
    String upperText() {
      return text.toUpperCase(Locale.ROOT);
    }
  }

  private record RelationCheck(int nextIndex, SqlViolation violation) {}

  private static final class SqlGuardException extends RuntimeException {
    private final SqlViolation violation;

    private SqlGuardException(final SqlViolation violation) {
      this.violation = violation;
    }
  }
}
