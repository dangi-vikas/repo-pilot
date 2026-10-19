# ADR 0010 — Heap dumps: a census, not a memory model

- **Status:** Accepted
- **Date:** 2026-10-19
- **Deciders:** RepoPilot maintainers

## Context

A heap dump is the most complete evidence a Java process can produce and the easiest to draw the
wrong conclusion from. It is a **byte-for-byte image of the heap at one instant**: every object, its
class, its field values, and the garbage-collection roots the collector would start from. What it does
not contain is the one number every heap tool is asked for first — *what would be freed if this became
unreachable* — because that is not in the file. It is a property of the whole object graph, computed
from it, never read out of it.

Everything an operator believes about a heap dump is downstream of that distinction, and so is this
phase. Two families of tool exist, and they answer different questions:

| Question | Tool | What its number means |
| --- | --- | --- |
| How many objects of each type, and how much data do they hold? | This reader, and `jmap -histo` | A count, and a size, per type |
| What would be freed if this object/type/classloader went away? | A dominator-tree tool | A reachability computation over the whole graph |

The second is what makes a heap dump useful for hunting a leak, and this build does not do it. The
decision below is therefore mostly about what the screen must say in order not to be read as if it
did.

### What was established by probing, not by reading a specification

HPROF has no specification the JDK ships. It was verified by taking real dumps from **JDK 21.0.5**
with `jcmd <pid> GC.heap_dump` and `jmap -dump:format=b,file=…` and walking them record by record, and
four facts changed the code:

1. **The header is 19 bytes, then a `u4` identifier width, then a `u8` timestamp in epoch
   milliseconds — and the format defines it as UTC.** A `1.0.2` dump from a 64-bit JVM declares
   `idSize = 8` even with compressed references, and every reference *inside* an object is written at
   that width regardless of `-XX:UseCompressedOops`.
2. **An `INSTANCE_DUMP` carries the number of bytes that follow it**, and that number already covers
   the fields the class inherits. The reader needs no field layout, no superclass walk and no symbol
   resolution to advance: `classObjectId` plus that count is the whole record. Reading class metadata
   to walk instances would have been work with nothing to show for it.
3. **A `STRING` record's body is an identifier followed by the text** (`length = idSize + textBytes`),
   not a length-prefixed string. `LOAD_CLASS` names a class by *pointing at one of those identifiers*,
   so class names are only reachable through the string table — which is why the reader has two passes
   (below).
4. **`ROOT_THREAD_OBJECT` writes eight bytes after the identifier, not the four-byte thread serial
   that summaries of the format give.** This is not a detail: reading it as four bytes desynchronises
   the walk, and the desynchronisation was reproduced deliberately — with four, the parser hits
   `0x58` inside a valid dump within one megabyte. The reader refuses an unknown record tag rather
   than guessing a width, so what that reproduced was a refusal, not a wrong number. It is the clearest
   case in this codebase for refusing instead of approximating.

### What the numbers were checked against

A dump of a JVM with a deliberately countable population (1 000 × `byte[1024]`, 250 × `int[256]`,
500 nodes and 120 buckets, all held by a live reference from a `static` list) was read twice: by this
reader, and by `jcmd <pid> GC.class_histogram`, one second apart on the same process. The subject is
compiled before it starts rather than run in single-file source mode, so the JVM holds nothing of its
own but the platform it loads. The object counts agreed **exactly** for the three largest types:

| Type | `-histo` objects | This reader, objects | `-histo` estimate | This reader, recorded bytes |
| --- | ---: | ---: | ---: | ---: |
| `byte[]` | 4 375 | **4 375** | 1 227 192 | 1 147 721 |
| `java.lang.String` | 3 172 | **3 172** | 76 128 | 44 408 |
| `HeapProbe$Node` | 500 | **500** | 12 000 | 10 000 |

Two facts follow from the two byte columns, and both are why every byte row carries its basis in
prose:

- **Recorded bytes are the field and element data the file wrote, not the size the JVM gave the
  object.** A `Node` is 24 bytes to the JVM — a 12-byte header plus a reference, an array reference
  and an `int` under compressed oops — and 20 as recorded, because the dump writes identifiers at its
  declared width instead of the width the JVM used in memory. `byte[]` is 6.5 % smaller here for the
  same reason, and the gap is the header and the padding.
- **`GC.heap_dump` writes only what is reachable.** The histogram, taken a second earlier, listed
  19 403 objects; the dump's census is 18 501. Taking a dump collects before it writes, which is also
  why the command has an `-all` flag for the objects that are not reachable. A census of this file is
  a census of what the process was *holding*, not of everything it had allocated.

## Decision

**RepoPilot reads an HPROF binary dump, reports the census it recorded, and computes no retained size,
applies no threshold and passes no verdict.**

1. **Only the binary HPROF form is read, and everything else is refused by name at upload.** A gzip
   stream (`GC.heap_dump -gz=<level>`) is refused with the `gzip -d` command that uncompresses it; a
   class histogram (`jmap -histo`, `GC.class_histogram`) is refused with the command that writes a
   real dump, because a histogram has counts and no objects and therefore nothing RepoPilot could
   check; a thread dump sends the operator to the reader that actually handles it. Every refusal is a
   `422 UNSUPPORTED_INPUT` **before** an artefact is stored or a run row exists.

2. **A compressed dump is refused, not decompressed in the job.** A 2.6 MB gzip file expanded to
   10 MB in the test case; the ratio is the problem. Decompressing inside the analysis would mean a
   file that passed the upload's size limit could still hand a job an unbounded amount of work. The
   refusal names the one-line fix, which is better than a job that runs for ten minutes inside a
   worker slot the queue needed.

3. **The reader makes two passes.** Pass one reads the class table (`LOAD_CLASS` → object id and name
   string id). Pass two walks the heap *and* keeps only the text of the strings pass one asked for, so
   the string table — 68 671 records in the 10 MB dump — cannot bound the reader's memory. Names are
   resolved after the walk, from identifiers to text, which is also why a name that never arrives is
   *counted* (`objects.unnamed`) rather than invented.

4. **Every byte figure is "recorded bytes", and every row says what that excludes.** For an instance
   it is the field values the dump wrote, including inherited fields; for an array it is the element
   data. It excludes every object header, all class metadata, and everything reachable from the
   object. The rows state that they are **smaller than the memory the objects occupy** and are **not a
   retained size**. Both sentences are stored as row attributes, not in the page's copy alone, so
   they travel with the number through any client.

5. **No dominator tree, no retained size, no leak verdict.** `produced.retainedSizesComputed` is
   `false` in the provenance, the run summary says "no retained size computed", and the UI repeats it.
   A type near the top of these tables is a type whose objects *hold a lot of recorded data*; it is
   not a leak and not a problem. The largest-single-object row exists precisely because a byte total
   cannot answer "is something enormous in here", and it is stated as one object's recorded bytes.

6. **No thresholds and no severity.** Whether a heap is too full is a policy about a process's purpose
   and its `-Xmx`, and this reader has been told neither. Everything is a `SIZE`/`SUM`,
   `COUNTER`/`COUNT` or `MAX` measurement on [ADR 0008](0008-runtime-evidence.md)'s contract.

7. **The `heapdump.` namespace.** Every key starts with it, so a heap dump, a thread dump and a JFR
   recording in one repository cannot be mistaken for each other's rows — the metric family and the
   UI's filter both read that first segment.

8. **The type tables are cut at twenty, and the cut is stated.** `types.bytes.observed` carries the
   number of types the dump held, `types.bytes.reported` the number the tables list, and the
   difference in prose. A share is a share of *the whole census*, not of the twenty rows shown, and the
   row's `shareBasis` says so. A type's name appears in exactly one place — the scope of its own row,
   next to the number derived from it.

9. **Two type tables, because they answer different questions.** A heap can be dominated by a few
   enormous arrays or by millions of tiny objects, and only one of those is visible in each table.
   Sorting by bytes and by object count are different views of the same walk.

10. **An unknown record tag, or an unknown field type code, is a hard refusal.** The reader does not
    skip a record whose size it does not know. A guessed size produces a walk that stays inside the
    file and returns numbers that nothing downstream can check, which is the failure mode this whole
    decision is built to avoid.

11. **The provenance is narrow on purpose.** It records the header (format version, identifier width
    and why that is not the width of a reference), the file's size, the timestamp the format defines as
    UTC, the string count, the census, and the fact that no retained size was computed. It records **no
    type names in bulk, no field values and no object identities**. A heap dump is the application's own
    data; RepoPilot reads it for its shape, and the only place a type name is repeated is the row it
    belongs to.

12. **A heap dump is process memory, and the flag stays `true`.** `RuntimeArtifactType.HEAP_DUMP`
    reports `containsProcessMemory = true`, unlike a thread dump. The UI's warning — that the file
    contains "any string or credential that was live in it" — is driven by that flag and is not
    softened by the fact that only derived numbers are stored.

13. **Bounds that are backstops, not policy:** `MAX_TYPES = 500_000`, `MAX_NAME_BYTES = 65_535`, a
    checkpoint every 50 000 records (so a cancelled job stops promptly) and a progress report every
    8 MiB. None of them is a judgement about a heap; they exist so that a malformed or hostile file
    cannot make the reader allocate without bound.

14. **The census is of the objects the process was still holding, and the product says so.** Taking a
    dump collects first, so an object that had already become garbage is not in the file: a histogram of
    the same JVM taken a second before its dump listed 19 403 objects where the dump holds 18 501, which
    is also why `GC.heap_dump` has an `-all` flag. "How many objects are in this heap" therefore has two
    defensible answers, and the one in front of the operator is stated: on the census row as an
    attribute, in the run summary, and under the tables on the screen.

## Consequences

- **"What is in this heap?" has a precise answer.** Object counts and recorded bytes per type, exact
  from the file, with the counts verified against `jmap -histo` for three types on the same process and
  the byte totals shown to be smaller by the header and padding `-histo` estimates. What it is *not* is
  an answer to "what is using the memory", because that needs reachability. The screen, the run summary
  and every byte row say so.
- **The refusal set is the feature.** A histogram, a gzipped dump, a thread dump and an HPROF 1.0.3
  file are each named and redirected, at upload, with the command that produces the right file. An
  operator who uploads the wrong artefact gets told which one they have.
- **The third reader cost no migration and one enum entry.** `AnalysisRunType.HEAP_ANALYSIS` already
  declared `producesRuntimeMeasurements = true`; the harness was `AbstractRuntimeAnalysisHandler`, and
  `HeapDumpAnalysisJobHandler` supplies a file type, a run type, a reader version, a subject and a
  map from report to drafts. ADR 0009 predicted this and it held.
- **A cancelled run keeps what it persisted**, exactly as the other two readers do, and the run row
  says `CANCELLED`. Nothing pretends a partial read is complete.
- **Unnamed objects are disclosed rather than folded in.** If a dump has objects whose class has no
  `LOAD_CLASS` record, they are in the totals, absent from both type tables, and reported as their own
  row with a sentence saying why the tables do not sum to the whole.
- **Still not claimed: anything about reachability.** No dominator tree, no retained size, no leak
  detection, no "this classloader is holding the heap". Comparing two dumps — the shape a trend would
  need — is also not implemented, and the `SNAPSHOT` caveat says so on every run.
- **A dump is read but not decompressed, and a histogram is read as a histogram.** Both are refusals
  with a command attached, which is the pattern established by `Thread.dump_to_file` in ADR 0009.
