# Changelog

## 1.1.3 - 2026-10-24

Second round of malformed-input hardening, completing the work started in 1.1.1 by covering the
JPEG segment parsers and the embedded-metadata parsers (Photoshop IRB, IPTC, ICC, JPEG-embedded
EXIF). Fuzzing the public `Metadata.readMetadata` entry point across 20,000 mutated inputs now
produces zero uncaught `RuntimeException`/`Error`s: a corrupt file either parses, skips the bad
element, or fails with a checked `IOException`. New JUnit tests cover one crafted input per finding
(21 tests total).

### Fixed
- **JPEG segment readers no longer crash on malformed data.** `readDHT`/`readDQT`/`readSOF`/
  `readSOS`/`readSegmentData` reject a segment length below 2 with `IOException` instead of
  `new byte[len-2]` (`NegativeArraySizeException`). `DHTReader`, `DQTReader`, `SOFReader` and
  `SOSReader` bounds-check every read against the segment body, so a truncated or garbage segment
  stops cleanly rather than throwing `ArrayIndexOutOfBoundsException`.
- **DQTReader quantization-table bug.** A C-style operator-precedence error
  (`offset + de_zig_zag_order[j]<<1` parsed as `(offset + index) << 1`) read 16-bit table entries
  from the wrong, often out-of-bounds, position; the shift is now parenthesised. An invalid
  precision nibble is skipped instead of throwing `IllegalArgumentException` from `QTable`.
- **SOF component count.** `numOfComponents` is read unsigned and clamped to the data actually
  present, so a `0x80`+ value no longer yields `new Component[negative]`.
- **SOS before SOF.** A scan header with no preceding frame header no longer throws
  `IndexOutOfBoundsException` (`readers.get(-1)`); a null frame reader is tolerated.
- **One malformed APPn segment no longer aborts the whole JPEG read.** The post-scan APPn loop
  catches and skips a segment that is too short for its identifier or otherwise corrupt.
- **ExtendedXMP reassembly is bounded and bounds-checked.** The 32-bit total length is rejected if
  negative or absurd (preventing `OutOfMemoryError`), the per-segment destination window is
  validated before `System.arraycopy`, and a missing GUID no longer NPEs.
- **Photoshop IRB (8BIM) parsing is bounds-checked.** A truncated or oversized resource block is
  skipped instead of throwing `ArrayIndexOutOfBounds`/`StringIndexOutOfBounds`/
  `IllegalArgumentException` (from `ArrayUtils.subArray`).
- **IPTC parsing is bounds-checked.** A dataset whose declared size runs past the buffer is
  skipped, so iterating the result no longer throws from `IPTCDataSet`.
- **JPEG-embedded EXIF thumbnail.** A missing length field no longer NPEs, and a negative/oversized
  thumbnail length is rejected with `IOException` instead of `NegativeArraySizeException`/
  `OutOfMemoryError`.
- **ICC profile iteration.** An unknown profile/device class or rendering intent now yields a
  descriptive fallback string instead of throwing `IllegalArgumentException` mid-iteration.
- **EXIF field iteration.** A per-tag formatter that throws on a malformed field (wrong element
  count, zero rational denominator, type mismatch) now falls back to the raw value instead of
  aborting iteration of the whole EXIF block.

### Changed
- Format-rejection errors are now `IOException` rather than unchecked `RuntimeException`: a file
  shorter than the 4-byte magic number (`PeekHeadInputStream`), an invalid PNG signature/header,
  and an invalid TIFF byte order/identifier. Callers catching `IOException` now cover these cases.

### Known limitations
- A TIFF field declaring a large-but-legal element count (e.g. ~196,000, as a 16-bit transfer
  function legitimately has) backed by the file-cache stream is read one element at a time and can
  take a few seconds for a crafted ~1 MB file. The parse completes and is bounded; it is a
  file-cache I/O cost, not a crash or unbounded memory use, and is not yet addressed because a fix
  means reworking the stream's read buffering with a correctness risk to the core TIFF decoder.

## 1.1.2 - 2026-10-24

Documentation only; no code or behavior changes.

### Fixed
- Cleared the seven Javadoc lint warnings emitted during `mvn javadoc:jar`: literal `<`/`&`
  characters in the `SmokeTest` usage comment and in `StringUtils` doc comments are now escaped
  (or wrapped in `{@code}`), and a `{@link String#...}` tag that was broken across a line in
  `StringUtils.containsRegex` is closed on one line. The generated API docs now render these
  sections correctly.

## 1.1.1 - 2026-10-24

Security hardening of the metadata readers against malformed and malicious input. These are
defensive fixes: well-formed files parse exactly as before (all bundled sample images are
unaffected), while corrupt or hostile files now fail with an `IOException` or are skipped, rather
than crashing the caller with an uncaught `RuntimeException`/`Error` or exhausting memory. A new
JUnit 5 suite under `test/` covers one crafted input per finding; it is test-scoped and not part
of the published jar.

### Security
- **XXE / external-entity protection in XMP parsing.** `XMLUtils` created its
  `DocumentBuilderFactory` with no security configuration, so XMP metadata from an untrusted image
  was parsed with DOCTYPE declarations and external entity/DTD resolution enabled. All three
  factory sites now disallow DOCTYPE declarations, disable external general/parameter entities and
  external DTD loading, enable secure processing, and clear the external-DTD/schema access
  properties. This closes XML External Entity file disclosure, SSRF, and entity-expansion vectors.
  The hardening is applied defensively so a non-JDK JAXP provider (e.g. an older Xerces shadowing
  the JDK parser under Lucee) is covered too.

### Fixed
- **PNG chunk length no longer drives an unchecked allocation.** `PNGMeta.readChunks` allocated
  `new byte[data_len]` directly from the 32-bit chunk length. A negative value threw
  `NegativeArraySizeException` and a large positive value (up to ~2 GB) exhausted the heap from a
  few-byte file. Lengths that are negative or above a 128 MB ceiling are now rejected with
  `IOException`.
- **PNG decompression bombs are capped.** `zTXt`/`iTXt` text and `iCCP` profile inflation appended
  without limit, so a small compressed chunk could expand until the heap was exhausted. Inflation
  now aborts with `IOException` past a 64 MB cap, and the null-separator scans in `TextReader`/
  `readICCProfile` are bounds-checked (no more `ArrayIndexOutOfBoundsException` /
  `StringIndexOutOfBoundsException` on truncated chunks).
- **BMP palette and data-offset fields are validated.** `BMPMeta.readPalette` trusted the
  file-supplied `colorsUsed`, `bitsPerPixel`, and `dataOffset`, allowing `NegativeArraySizeException`
  (from integer overflow on `colorsUsed * 4` or a negative skip) or multi-gigabyte allocations.
  These are now range-checked and rejected with `IOException`.
- **GIF truncation no longer crashes.** `GIFMeta.readFrame` fed the result of `is.read()` (which is
  `-1` at end of stream) into `new byte[len]` / `skipFully`, throwing `NegativeArraySizeException`
  on a stream truncated inside an extension block. The sub-block loops now treat `-1` as a
  terminator, and `IOUtils.skipFully` rejects a negative count.
- **ICC tag table is bounds-checked.** `ProfileTagTable.read` looped over a file-supplied tag count
  and did `new byte[dataLength]` + `System.arraycopy` with a file-supplied offset and length,
  allowing `NegativeArraySizeException`, `OutOfMemoryError`, and `IndexOutOfBoundsException`. The
  tag count is clamped to what the buffer can hold and any tag whose data slice falls outside the
  profile is skipped.
- **Cyclic TIFF IFD chains no longer loop forever.** `TIFFMeta.readIFDs` followed the "next IFD"
  pointer with no cycle detection, so an IFD pointing back at itself (or an earlier IFD) looped and
  appended IFD objects until memory was exhausted. Visited offsets are now tracked and a repeat
  stops the walk, with a 1024-IFD safety cap. The `IFD`-type sub-directory recursion is likewise
  guarded against self-reference and empty counts, and now reads each referenced sub-IFD (it
  previously re-read the first one).
- **TIFF field counts are capped.** An IFD entry could declare up to ~2 billion elements, forcing a
  huge array allocation (and, for `RATIONAL`, a `2 * count` sign overflow) before any byte was
  read. Counts above 1,048,576 are now skipped; this clears the largest legitimate fields (a 16-bit
  transfer function is 196,608 entries) while blocking the allocation bomb.

### Known limitations
- Some deeply-embedded JPEG segment parsers (`DHTReader`, `DQTReader`, `SOFReader`) and parts of the
  Photoshop IRB / IPTC / JPEG-embedded EXIF thumbnail paths can still throw an uncaught runtime
  exception on malformed input. These produce a crash, not information disclosure or unbounded
  memory use. Callers reading untrusted input should catch broadly, not only `IOException`.
- A TIFF field declaring a large-but-legal element count (e.g. ~196,000) backed by the file-cache
  stream is read one element at a time and can take several seconds for a crafted ~1 MB file. This
  is a parse-time (not memory or crash) cost in the I/O layer and is not yet addressed.

## 1.1.0 - 2026-10-22

First release of the akitogo fork, published to Maven Central as `com.akitogo:pixymeta`.
Upstream `com.github.dragon66:pixymeta` was never released.

### Fixed
- `TIFFMeta.readIFD()` no longer throws `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0` when a LONG/SLONG IFD entry declares count 0: the EXIF_SUB_IFD / GPS_SUB_IFD / EXIF_INTEROPERABILITY_OFFSET dispatch accessed `ldata[0]` unconditionally. All three checks now guard on `ldata.length > 0`. Also, an IFD entry whose count reads as negative (corrupt data) is skipped instead of crashing array allocation with `NegativeArraySizeException`. Observed in production at akibase (import queue) on JPEGs whose EXIF IFD carries a zero-count sub-IFD pointer, e.g. 25187327.jpg; such files aborted all metadata reading (IPTC/XMP included) even though only the EXIF block was malformed.
- `IPTCDataSet.getTagName()` no longer throws `RuntimeException("Unknown IPTC record")` when the IPTC record number does not map to a known `IPTCRecord` enum value. Falls back to `IPTCApplicationTag.UNKNOWN` so parsing continues for the rest of the file's IPTC tags. The throw was previously the only reachable branch of the inner `switch` at the `case UNKNOWN` block (the inner switch dispatched on the same `fromRecordNumber(recordNumber)` as the outer switch — if the outer hit `UNKNOWN`, the inner could only ever hit `UNKNOWN` too), so every file with a proprietary/non-standard IPTC record number aborted metadata reading. Observed in production at akibase-f1online generating hundreds of error log entries per scheduler tick for assets with IPTC blocks written by older Photoshop pipelines, museum archive imports, and custom Adobe Bridge schemas.

### Changed
- Upgraded minimum Java version from 8 to 21
- Upgraded SLF4J from 1.7.12 to 2.0.17
- Removed Log4j entirely. The library only uses the SLF4J API; the Log4j 2 runtime dependencies
  and the bundled `log4j2.xml` forced a logging backend onto every consumer and clashed with the
  SLF4J binding already present in hosts such as Lucee and BoxLang. Consumers now pick their own
  binding. `slf4j-simple` is available in test scope for local development.
- Removed the unused `jai-imageio-core` dependency (nothing in the sources imports it; the only
  reference was a Javadoc comment).
- Updated Eclipse `.classpath` for JavaSE-21 and the trimmed dependency set
- Removed the Ant build (`build.xml`, `lib/`, `dist/`, and the stale compiled classes that were
  tracked under `build/`). Maven is the only build now.

### Why
- Log4j 1.x is end-of-life with known CVEs
- Lucee 6.x ships with SLF4J 2.x, requiring alignment
- Java 21 is the current LTS release

## 2019-06-21
- Added code for JPGMeta to return the removed metadata as a map

## 2019-05-14
- Write IPTC to normal TIFF IPTC tag instead of PhotoShop IRB block

## 2017-02-13
- Fixed bug with JPEGTweaker.insertXMP

## 2016-08-16
- Added support for Unicode string data to IPTCDataSet

## 2016-04-06
- Moved XMP to new package and make it abstract

## 2016-03-30
- Added insertTextChunk() to PNGMeta
- Rewrite JPEGMeta writeComment() to leverage COMBuilder

## 2015-11-07
- Added Metadata multiple comments support

## 2015-09-30
- Added mergesort() to ArrayUtils

## 2015-09-27
- Added shallowClose() to stream classes

## 2015-09-26
- Added insertComment() to Metadata

## 2015-09-19
- Added closeAll() to random access stream

## 2015-09-16
- Added insertComment() to GIFMeta to insert comment block

## 2015-07-19
- Renamed MetadataType.PHOTOSHOP to MetadataType.PHOTOSHOP_IRB

## 2015-07-12
- Added Metadata support for JPEG APP0 (JFIF)
- Fixed bug with JPEGTweaker removing APP13

## 2015-07-09
- Rewrote TextualChunks to work with multiple textual chunks
- Added Metadata support for PNG tIME chunk

## 2015-07-08
- Made Metadata implements MetadataReader directly
- Removed unnecessary separate MetadataReader implementations

## 2015-07-06
- Added insertXMP(InputStream, OutputStream, XMP)

## 2015-07-05
- Added support for PNG Textual chunk

## 2015-07-03
- Removed NativeMetadata and related code

## 2015-07-02
- Added support for JPEG APP12 and APP14

## 2015-07-01
- Added support for non-standard XMP identifier

## 2015-06-12
- Replaced console output with slf4j logging

## 2015-05-01
- Became a stand-alone library

## 2015-04-29
- Renamed XMLUtils findAttribute() to getAttribute()

## 2015-04-10
- Meta data related changes

## 2015-04-07
- Merge Adobe IPTC and TIFF IPTC if both exist

## 2015-03-14
- Extracted from icafe
