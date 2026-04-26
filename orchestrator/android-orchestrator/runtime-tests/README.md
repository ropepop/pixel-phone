# Android Runtime Shell Tests

These tests validate shell runtime assets under:
- `android-orchestrator/app/src/main/assets/runtime/entrypoints`
- `android-orchestrator/app/src/main/assets/runtime/templates`

Run all tests:

```bash
for t in android-orchestrator/runtime-tests/test_*.sh; do
  bash "$t"
done
```
