# Changelog

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
