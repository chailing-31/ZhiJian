# Batch workbench V4 — implementation contract

Version: batch-workbench-v4-20261013. Read the package README for deployment. This is a local controlled-demo implementation, not a production release.

## New endpoints (Spring Boot, prefix /api in browser)

| Method / route | Purpose |
|---|---|
| GET /batches/{id}/batch-workbench | One authoritative current workbench snapshot |
| POST /batches/{id}/inspection-rounds | Explicit round, sampling scope, operator and optimistic prior round ID |
| POST /batches/{id}/inspection-rounds/{round}/images | Explicit historical image membership / sample code, evidence fingerprint required |
| POST /batches/{id}/batch-assessments | Manual A/B/C/REJECT, intended use, basis, current round fingerprint and prior assessment ID |
| POST /batches/{id}/assessment-processing-advice | Read approved grade server-side; call existing B; store provenance locally |
| POST /batches/{id}/batch-operations | Actual sorting, processing or reject-disposition record; not a plan or device command |
| POST /batches/{id}/batch-summary/preview | Generate exact whitelist publication content |
| POST /batches/{id}/batch-summary/publish | Save approved preview fingerprint and publication flags |
| GET /trace/{code}/batch-summary | Only saved public snapshot and freshness-of-source flag |

The existing /processing-records/{id}/adopt endpoint now checks batch assessment provenance where the batch has an active Q4 round. It requires expected_processing_fingerprint, nonempty note, valid source, actual sorting, B status suggested and no coldchain hold. Direct unlinked requests cannot bypass the check once a Q4 round is active. Legacy records remain readable.

Mutation errors use {code,message}. New create/approve/advice/operation/publication requests include a canonical UUID request_id; retries with the same body return the saved record, while changed bodies with the same key return409. Evidence assignment uses optimistic evidence fingerprint. Response includes persisted/id/workbench; the UI additionally re-reads the workbench after successful saves.

## Grade, use and detector semantics

Detector outputs are immutable image observations. Batch grade is a separate explicit manual assessment; there is no threshold averaging, no last-image-to-batch override, and no zero-box-to-A inference. Grade policy manual-batch-assessment-v1 names A 优选外观, B 一般外观，需分选, C 用途待评估, REJECT 人工拒收. Grade basis and scope are operator declarations, not an established national standard.

Uses: premium_sorting, general_sorting, processing_evaluation, isolate_return, undetermined. A can enter premium/general sorting; B general sorting; C remains evaluation/undetermined; REJECT must be isolate_return. A cannot contradict confirmed defects in the current round. A separate post-sorting round can be evaluated on new evidence. No automatic juice/drying eligibility.

## Evidence and provenance

Membership records distinguish inspection image IDs from explicitly assigned sample codes. Same normalized pixel identity cannot claim multiple samples. Unknown sample identity yields identified_sample_count=null; fruit_defect_rate always null. Round evidence includes model version, image identity, original prediction hash, latest review revision/hash, sample code and human findings. The approval stores an immutable JSON snapshot and fingerprint.

New uploads enroll in the current round inside the existing image transaction. New membership, sample codes or review versions invalidate older approvals. All source mutations serialize via the same batch row lock. Processing links bind current assessment ID/revision/evidence fingerprint and intended use. The source fields stay in C's inputs_json and q4_processing_links; strict B requests receive only its existing fields. No modification to B's algorithms or rule values.

Independent historical inputs are not counted as a current linked process. Non-suggested B statuses cannot be adopted. Immutable adoption history is appended while retaining the current adopted_values field for compatibility. Changing adoption invalidates prior execution fingerprints; actual execution is a separate human declaration with timestamp.

## Coldchain and report integration

C persists the full B /coldchain/check analysis returned for each batch/source when a read is submitted. Old data without the matching snapshot is unverified, not normal. Re-importing the same original data through existing idempotency checks records the analysis. No synthetic fallback is generated.

UI presents B status and episodes without reinterpreting recovery as manual disposition. Existing unresolved alerts or ongoing episodes prevent new linked adoption/execution; absent future coldchain records do not prevent earlier-stage work. Simulation and sensor streams remain separate. Current limitation: one monitored object per batch/source; device ID isolation and automatic freshness gates are not implemented. No release certification is inferred.

Internal /batches/{id}/report includes batch_workbench, assessment scope, operation evidence, history, statuses and provenance. Missing Q4 data is a visible warning, never silent success. Public snapshots use a fixed field whitelist; grade/use require separate publish flags; no raw image, score, internal note, operator or supplier is published. Subsequent source changes mark the saved snapshot historical. Older public events are not deleted or retroactively unpublished.

## Storage and deployment

Only eight additive q4_* tables. Select smart_fresh_c_review explicitly using package launcher; no USE, destructive DDL or data seeding in migration. On Windows retain the old verified model and original evidence storage. Package includes cumulative V2/V3 business/UI changes but does not patch the AI inference engine or switch manifests. Git HEAD unchanged; local feature branch and guarded source backups.

Testing scope and remaining full-stack gates are in package VALIDATION.json. The new H2/JUnit suite mocks B; actual HTTP acceptance deliberately labels every synthetic business value and uses a fresh Q4-* test batch. Neither test is model accuracy or real-business signoff.
