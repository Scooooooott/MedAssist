package com.medassist.agent.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/** Production generation store backed by Redis Hash metadata and native Redis Streams. */
public final class RedisGenerationStore implements GenerationStore {
  private static final TypeReference<Map<String, Object>> PAYLOAD_TYPE = new TypeReference<>() {};
  private static final DefaultRedisScript<String> CREATE_SCRIPT =
      script(
          """
          local existing_hash = redis.call('HGET', KEYS[1], 'request_hash')
          if existing_hash then
            if existing_hash ~= ARGV[1] then return 'IDEMPOTENCY_CONFLICT' end
            return 'EXISTING:' .. redis.call('HGET', KEYS[1], 'generation_id')
          end
          redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[10])
          redis.call('ZREMRANGEBYSCORE', KEYS[4], '-inf', ARGV[10])
          if redis.call('ZCARD', KEYS[4]) >= tonumber(ARGV[11]) then return 'ACTIVE_LIMIT' end
          if redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[12]) then return 'ACTIVE_LIMIT' end
          redis.call('HSET', KEYS[2],
            'generation_id', ARGV[2], 'owner_subject', ARGV[3], 'roles', ARGV[4],
            'policy_version', ARGV[5], 'request_hash', ARGV[1], 'status', ARGV[6],
            'created_at', ARGV[7], 'expires_at', ARGV[8], 'terminal_event_id', '',
            'event_count', '0', 'buffered_bytes', '0')
          redis.call('PEXPIRE', KEYS[2], ARGV[13])
          redis.call('HSET', KEYS[1], 'request_hash', ARGV[1], 'generation_id', ARGV[2])
          redis.call('PEXPIRE', KEYS[1], ARGV[9])
          redis.call('ZADD', KEYS[3], ARGV[8], ARGV[2])
          redis.call('ZADD', KEYS[4], ARGV[8], ARGV[2])
          redis.call('PEXPIRE', KEYS[4], ARGV[13])
          return 'CREATED:' .. ARGV[2]
          """);
  private static final DefaultRedisScript<Long> TRANSITION_SCRIPT =
      longScript(
          """
          local current = redis.call('HGET', KEYS[1], 'status')
          if not current then return 0 end
          if not string.find(ARGV[1], '|' .. current .. '|', 1, true) then return 0 end
          redis.call('HSET', KEYS[1], 'status', ARGV[2], 'terminal_event_id', ARGV[3])
          if ARGV[4] == '1' then
            redis.call('ZREM', KEYS[2], ARGV[5])
            redis.call('ZREM', KEYS[3], ARGV[5])
          end
          return 1
          """);
  private static final DefaultRedisScript<String> APPEND_SCRIPT =
      script(
          """
          local current = redis.call('HGET', KEYS[1], 'status')
          if not current or not string.find(ARGV[1], '|' .. current .. '|', 1, true) then
            return 'STATE_CONFLICT'
          end
          local count = tonumber(redis.call('HGET', KEYS[1], 'event_count') or '0')
          local bytes = tonumber(redis.call('HGET', KEYS[1], 'buffered_bytes') or '0')
          if count >= tonumber(ARGV[8]) - 1 then return 'EVENT_LIMIT' end
          if bytes + tonumber(ARGV[7]) + tonumber(ARGV[11]) > tonumber(ARGV[9]) then
            return 'BYTE_LIMIT'
          end
          local event_id = redis.call('XADD', KEYS[2], '*',
            'generation_id', ARGV[2], 'type', ARGV[3], 'schema_version', ARGV[4],
            'created_at', ARGV[5], 'payload', ARGV[6], 'bytes', ARGV[7])
          redis.call('XTRIM', KEYS[2], 'MAXLEN', '=', ARGV[8])
          redis.call('PEXPIRE', KEYS[2], ARGV[10])
          redis.call('HINCRBY', KEYS[1], 'event_count', 1)
          redis.call('HINCRBY', KEYS[1], 'buffered_bytes', ARGV[7])
          return event_id
          """);
  private static final DefaultRedisScript<String> TERMINAL_SCRIPT =
      script(
          """
          local current = redis.call('HGET', KEYS[1], 'status')
          if not current or not string.find(ARGV[1], '|' .. current .. '|', 1, true) then
            return 'STATE_CONFLICT'
          end
          local count = tonumber(redis.call('HGET', KEYS[1], 'event_count') or '0')
          local bytes = tonumber(redis.call('HGET', KEYS[1], 'buffered_bytes') or '0')
          if count >= tonumber(ARGV[9]) then return 'EVENT_LIMIT' end
          if bytes + tonumber(ARGV[8]) > tonumber(ARGV[10]) then return 'BYTE_LIMIT' end
          local event_id = redis.call('XADD', KEYS[2], '*',
            'generation_id', ARGV[3], 'type', ARGV[4], 'schema_version', ARGV[5],
            'created_at', ARGV[6], 'payload', ARGV[7], 'bytes', ARGV[8])
          redis.call('XTRIM', KEYS[2], 'MAXLEN', '=', ARGV[9])
          redis.call('PEXPIRE', KEYS[2], ARGV[11])
          redis.call('HINCRBY', KEYS[1], 'event_count', 1)
          redis.call('HINCRBY', KEYS[1], 'buffered_bytes', ARGV[8])
          redis.call('HSET', KEYS[1], 'status', ARGV[2], 'terminal_event_id', event_id)
          redis.call('ZREM', KEYS[3], ARGV[3])
          redis.call('ZREM', KEYS[4], ARGV[3])
          return event_id
          """);

  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;
  private final String prefix;
  private final Duration metadataRetentionGrace;

  public RedisGenerationStore(
      final StringRedisTemplate redis,
      final ObjectMapper objectMapper,
      final String keyPrefix,
      final Duration metadataRetentionGrace) {
    this.redis = java.util.Objects.requireNonNull(redis, "redis");
    this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper");
    this.prefix = keyPrefix + ":{generation}:";
    this.metadataRetentionGrace = metadataRetentionGrace;
  }

  @Override
  public CreationResult create(
      final GenerationSession session,
      final String idempotencyKey,
      final int maxActivePerUser,
      final int maxActiveGlobal) {
    final Duration ttl = Duration.between(session.createdAt(), session.expiresAt());
    final String result =
        execute(
            CREATE_SCRIPT,
            List.of(
                idempotencyKey(session.ownerSubject(), idempotencyKey),
                metadataKey(session.generationId()),
                globalActiveKey(),
                userActiveKey(session.ownerSubject())),
            session.requestHash(),
            session.generationId(),
            session.ownerSubject(),
            encodeRoles(session.roles()),
            session.policyVersion(),
            session.status().name(),
            session.createdAt().toString(),
            Long.toString(session.expiresAt().toEpochMilli()),
            Long.toString(ttl.toMillis()),
            Long.toString(session.createdAt().toEpochMilli()),
            Integer.toString(maxActivePerUser),
            Integer.toString(maxActiveGlobal),
            Long.toString(ttl.plus(metadataRetentionGrace).toMillis()));
    if ("IDEMPOTENCY_CONFLICT".equals(result)) {
      throw new GenerationStoreException(
          GenerationStoreException.Reason.IDEMPOTENCY_CONFLICT,
          "idempotency key was reused with a different request");
    }
    if ("ACTIVE_LIMIT".equals(result)) {
      throw new GenerationStoreException(
          GenerationStoreException.Reason.ACTIVE_LIMIT, "generation active-session limit reached");
    }
    final boolean created = result.startsWith("CREATED:");
    final String generationId = result.substring(result.indexOf(':') + 1);
    final GenerationSession stored =
        created
            ? session
            : find(generationId)
                .orElseThrow(
                    () ->
                        new GenerationStoreException(
                            GenerationStoreException.Reason.UNAVAILABLE,
                            "idempotent generation metadata is unavailable"));
    return new CreationResult(stored, created);
  }

  @Override
  public Optional<GenerationSession> find(final String generationId) {
    try {
      final Map<Object, Object> values = redis.opsForHash().entries(metadataKey(generationId));
      if (values.isEmpty()) {
        return Optional.empty();
      }
      final String terminalId = value(values, "terminal_event_id");
      return Optional.of(
          new GenerationSession(
              value(values, "generation_id"),
              value(values, "owner_subject"),
              decodeRoles(value(values, "roles")),
              value(values, "policy_version"),
              value(values, "request_hash"),
              GenerationStatus.valueOf(value(values, "status")),
              Instant.parse(value(values, "created_at")),
              Instant.ofEpochMilli(Long.parseLong(value(values, "expires_at"))),
              terminalId.isBlank() ? null : terminalId));
    } catch (final DataAccessException | IllegalArgumentException exception) {
      throw unavailable(exception);
    }
  }

  @Override
  public boolean transition(
      final String generationId,
      final Set<GenerationStatus> expected,
      final GenerationStatus next,
      final String terminalEventId) {
    final GenerationSession session = find(generationId).orElse(null);
    if (session == null) {
      return false;
    }
    final Long result =
        execute(
            TRANSITION_SCRIPT,
            List.of(
                metadataKey(generationId),
                globalActiveKey(),
                userActiveKey(session.ownerSubject())),
            encodeStatuses(expected),
            next.name(),
            terminalEventId == null ? "" : terminalEventId,
            next.terminal() ? "1" : "0",
            generationId);
    return result != null && result == 1L;
  }

  @Override
  public GenerationEvent append(
      final GenerationEvent event,
      final Set<GenerationStatus> expected,
      final int maxEvents,
      final long maxBufferedBytes,
      final Duration retention) {
    final String payload = encodePayload(event.payload());
    final long bytes = estimatedBytes(payload);
    final String result =
        execute(
            APPEND_SCRIPT,
            List.of(metadataKey(event.generationId()), streamKey(event.generationId())),
            encodeStatuses(expected),
            event.generationId(),
            event.type().wireName(),
            event.schemaVersion(),
            event.createdAt().toString(),
            payload,
            Long.toString(bytes),
            Integer.toString(maxEvents),
            Long.toString(maxBufferedBytes),
            Long.toString(retention.toMillis()),
            Long.toString(TERMINAL_EVENT_RESERVE_BYTES));
    return event.withEventId(requireAppendResult(result));
  }

  @Override
  public Optional<GenerationEvent> appendTerminal(
      final GenerationEvent event,
      final Set<GenerationStatus> expected,
      final GenerationStatus terminalStatus,
      final int maxEvents,
      final long maxBufferedBytes,
      final Duration retention) {
    if (!terminalStatus.terminal() || !event.terminal()) {
      throw new IllegalArgumentException("terminal event and status are required");
    }
    final GenerationSession session = find(event.generationId()).orElse(null);
    if (session == null) {
      return Optional.empty();
    }
    final String payload = encodePayload(event.payload());
    final long bytes = estimatedBytes(payload);
    final String result =
        execute(
            TERMINAL_SCRIPT,
            List.of(
                metadataKey(event.generationId()),
                streamKey(event.generationId()),
                globalActiveKey(),
                userActiveKey(session.ownerSubject())),
            encodeStatuses(expected),
            terminalStatus.name(),
            event.generationId(),
            event.type().wireName(),
            event.schemaVersion(),
            event.createdAt().toString(),
            payload,
            Long.toString(bytes),
            Integer.toString(maxEvents),
            Long.toString(maxBufferedBytes),
            Long.toString(retention.toMillis()));
    if ("STATE_CONFLICT".equals(result)) {
      return Optional.empty();
    }
    return Optional.of(event.withEventId(requireAppendResult(result)));
  }

  @Override
  public List<GenerationEvent> readAfter(
      final String generationId, final String lastEventId, final int limit) {
    try {
      final String cursor = lastEventId == null || lastEventId.isBlank() ? "0-0" : lastEventId;
      final List<MapRecord<String, Object, Object>> records =
          redis
              .opsForStream()
              .range(
                  streamKey(generationId),
                  Range.rightOpen(cursor, "+"),
                  Limit.limit().count(limit));
      if (records == null) {
        return List.of();
      }
      return records.stream().map(this::decodeEvent).toList();
    } catch (final DataAccessException | IllegalArgumentException exception) {
      throw unavailable(exception);
    }
  }

  private GenerationEvent decodeEvent(final MapRecord<String, Object, Object> record) {
    final Map<Object, Object> values = record.getValue();
    try {
      return new GenerationEvent(
          record.getId().getValue(),
          value(values, "generation_id"),
          GenerationEventType.fromWireName(value(values, "type")),
          value(values, "schema_version"),
          Instant.parse(value(values, "created_at")),
          objectMapper.readValue(value(values, "payload"), PAYLOAD_TYPE));
    } catch (final JsonProcessingException exception) {
      throw unavailable(exception);
    }
  }

  private String requireAppendResult(final String result) {
    return switch (result) {
      case "STATE_CONFLICT" -> throw new GenerationStateConflictException();
      case "EVENT_LIMIT" ->
          throw new GenerationStoreException(
              GenerationStoreException.Reason.EVENT_LIMIT, "generation event buffer limit reached");
      case "BYTE_LIMIT" ->
          throw new GenerationStoreException(
              GenerationStoreException.Reason.BYTE_LIMIT, "generation byte buffer limit reached");
      default -> result;
    };
  }

  private String encodePayload(final Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload);
    } catch (final JsonProcessingException exception) {
      throw new IllegalArgumentException("generation event payload is invalid", exception);
    }
  }

  private <T> T execute(
      final org.springframework.data.redis.core.script.RedisScript<T> script,
      final List<String> keys,
      final String... arguments) {
    try {
      final T result = redis.execute(script, keys, (Object[]) arguments);
      if (result == null) {
        throw new GenerationStoreException(
            GenerationStoreException.Reason.UNAVAILABLE, "generation store returned no result");
      }
      return result;
    } catch (final GenerationStoreException exception) {
      throw exception;
    } catch (final DataAccessException exception) {
      throw unavailable(exception);
    }
  }

  private String metadataKey(final String generationId) {
    return prefix + "session:" + generationId + ":meta";
  }

  private String streamKey(final String generationId) {
    return prefix + "session:" + generationId + ":events";
  }

  private String idempotencyKey(final String subject, final String idempotencyKey) {
    return prefix + "idempotency:" + hash(subject) + ":" + hash(idempotencyKey);
  }

  private String globalActiveKey() {
    return prefix + "active:global";
  }

  private String userActiveKey(final String subject) {
    return prefix + "active:user:" + hash(subject);
  }

  private static String encodeRoles(final Set<String> roles) {
    return roles.stream().sorted().collect(Collectors.joining(","));
  }

  private static Set<String> decodeRoles(final String roles) {
    return Set.copyOf(Arrays.asList(roles.split(",")));
  }

  private static String encodeStatuses(final Set<GenerationStatus> statuses) {
    return statuses.stream()
        .sorted(Comparator.comparing(Enum::name))
        .map(Enum::name)
        .collect(Collectors.joining("|", "|", "|"));
  }

  private static String value(final Map<Object, Object> values, final String key) {
    final Object value = values.get(key);
    return value == null ? "" : value.toString();
  }

  private static long estimatedBytes(final String payload) {
    return payload.getBytes(StandardCharsets.UTF_8).length + 256L;
  }

  private static String hash(final String value) {
    try {
      return Base64.getUrlEncoder()
          .withoutPadding()
          .encodeToString(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (final NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static DefaultRedisScript<String> script(final String source) {
    return new DefaultRedisScript<>(source, String.class);
  }

  private static DefaultRedisScript<Long> longScript(final String source) {
    return new DefaultRedisScript<>(source, Long.class);
  }

  private static GenerationStoreException unavailable(final Exception cause) {
    return new GenerationStoreException(
        GenerationStoreException.Reason.UNAVAILABLE, "generation store is unavailable", cause);
  }
}
