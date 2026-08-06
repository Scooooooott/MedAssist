# Model Licenses

This file records model candidates for M0. A model can be used in a public demo only if its license permits the intended use.

| Model | Source | Purpose | License | Commercial use | Attribution | Domain restrictions | Decision |
|---|---|---|---|---|---|---|---|
| BAAI/bge-m3 | https://huggingface.co/BAAI/bge-m3 | Default embedding model | MIT | Allowed | Cite model card/paper | None identified | Approved default |
| Sophia-AI/bge-m3-onnx | https://huggingface.co/Sophia-AI/bge-m3-onnx | ONNX export candidate | MIT inherited from BGE-M3 | Allowed | Cite base model and export | None identified | Candidate only; verify hash before use |
| BAAI/bge-reranker-v2-m3 | https://huggingface.co/BAAI/bge-reranker-v2-m3 | M2 reranker candidate | Apache-2.0 | Allowed | Cite model card/papers | None identified | Approved candidate |
| cross-encoder/ms-marco-MiniLM-L-6-v2 | https://huggingface.co/cross-encoder/ms-marco-MiniLM-L-6-v2 | Lightweight reranker fallback | Apache-2.0 family model card; verify exact card before activation | Allowed if exact card remains permissive | Cite sentence-transformers and model | None identified | Candidate pending exact card review |
| urchade/gliner_small-v2.1 | https://huggingface.co/urchade/gliner_small-v2.1 | PHI NER supplement | Apache-2.0 | Allowed | Cite GLiNER paper/model card | Use v2 or v2.1 only; older GLiNER models can be non-commercial | Approved candidate |
| Microsoft Presidio | https://github.com/microsoft/presidio | PHI detection framework | MIT | Allowed | Preserve license notices | None identified | Approved framework |
| ncbi/MedCPT-Article-Encoder | https://huggingface.co/ncbi/MedCPT-Article-Encoder | Biomedical embedding comparison | Public-domain notice on model card | Allowed, with NIH/NCBI disclaimer respected | Cite MedCPT paper | Not for direct diagnostic use | Approved for offline experiment |
| FremyCompany/BioLORD-2023 | https://huggingface.co/FremyCompany/BioLORD-2023 | Biomedical embedding comparison | MIT contributions with UMLS/SNOMED licensing obligations | Not approved until UMLS/SNOMED obligations are satisfied | Cite BioLORD paper and maintain ontology license records | Requires valid UMLS/SNOMED licensing | Blocked for public demo |
| Local LLM candidates | M7.3 selection | Optional full-local mode | Per chosen model | Unknown until chosen | Per chosen model | Must support structured output reliably | Deferred to M7.3 |

## Rule

If a model license conflicts with an open GitHub repository, public demo, or commercial portfolio use, replace the model and record the decision in an ADR.
