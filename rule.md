# LANflix Rules

## Performance
- Performance is a top priority; keep user-facing paths fast.
- Avoid blocking the main thread; use coroutines with `Dispatchers.IO`.
- Favor streaming, paging, and incremental loading over large reads.
- Minimize allocations in hot paths; reuse objects where practical.
- Cache expensive results when safe, and avoid redundant work.
- Measure before and after; include perf notes for hot-path changes.

## File Size
- Maximum 200 lines per file.
- Split by responsibility when a file grows beyond the limit.

## Line Length
- Maximum 70 characters per line.
- Wrap long calls and strings to keep lines within the limit.

## Compliance
- New changes must follow these limits.
- Add tooling or scripts if needed to enforce checks.
