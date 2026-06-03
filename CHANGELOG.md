# Changelog

## Unreleased

### Changed
- Upgraded minimum Java version from 8 to 21
- Upgraded SLF4J from 1.7.12 to 2.0.17
- Replaced Log4j 1.2.17 with Log4j 2.25.0
- Replaced `log4j.properties` with `log4j2.xml` configuration
- Updated Eclipse `.classpath` for JavaSE-21 and new dependency JARs

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
