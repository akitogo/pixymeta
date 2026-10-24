# pixymeta
Java image metadata CRUD tool - a pure Java image metadata manipulation tool.
This is the meta data related part branched out from icafe repository.

> **About this fork.** PIXYMETA is the work of Wen Yu ([dragon66](https://github.com/dragon66)).
> The original repository is [dragon66/pixymeta](https://github.com/dragon66/pixymeta). This fork,
> maintained by [akitogo](https://www.akitogo.com), keeps the library building on current Java,
> fixes bugs found in production, and publishes releases to Maven Central. All credit for the
> design and the bulk of the code goes to the original author. See [Credits](#credits).

Requirements
----------------------------------------
- Java 21 or later
- SLF4J API 2.0.17

PIXYMETA logs through the SLF4J API only and ships no logging backend. Add the SLF4J binding of
your choice (Logback, Log4j 2, slf4j-simple, ...) to your own classpath; without one, SLF4J falls
back to its no-op logger.

Image metadata manipulation:
----------------------------------------
- JPEG and TIFF EXIF data manipulation
   * Insert EXIF data into JPEG.
   * Extract EXIF data from JPEG.
   * Remove EXIF data and other insignificant APPn segments from JPEG.
   * Insert EXIF data into TIFF.
   * Read EXIF data embedded in TIFF.
- JPEG and TIFF ICC Profile support
   * Insert ICC profile to JPEG and TIFF.
   * Extract ICC profile from JPEG and TIFF.
- JPEG and TIFF IPTC metadata support
   * Insert IPTC directly to TIFF via RichTiffIPTC tag.
   * Insert IPTC to JPEG via APP13 Photoshop IRB
   * Extract IPTC from both TIFF and JPEG
- JPEG and TIFF Photoshop IRB metadata support
   * Insert IRB into JPEG via APP13 segment
   * Insert IRB into TIFF via tag PHOTOSHOP.
   * Extract IRB data from both JPEG and TIFF.
- JPEG, GIF, PNG, TIFF XMP metadata support
   * Insert XMP metada into JPEG, GIF, PNG, and TIFF image
   * Extract XMP metadata from JPEG, GIF, PNG, and TIFF image
   * In case of JPEG, handle normal XMP and extendedXMP which cannot fit into one APP1 segment


How to compile
----------------------------------------
The project builds with Maven:

```bash
mvn clean package
```

The jar lands in `target/`. Run `mvn clean install` to make it available to other local Maven
projects. `mvn test` runs the JUnit 5 test suite under `test/` (test-scoped dependency only; it
is not part of the published jar).

Handling untrusted images
----------------------------------------
This library parses image metadata, so in many deployments it is handed files from end users or
the public web. As of 1.1.1 (and extended in 1.1.3) the readers are hardened against malformed and
hostile input: a corrupt file fails with an `IOException` (or the bad element is skipped) rather
than crashing the caller with an uncaught runtime exception or exhausting memory. Specifically:

- **XMP is parsed with a hardened XML reader.** DOCTYPE declarations are rejected and external
  entity and DTD resolution is disabled, so embedded XMP cannot trigger XML External Entity (XXE)
  file disclosure, server-side request forgery, or entity-expansion attacks.
- **Length and count fields are validated before allocation.** PNG chunk lengths, JPEG segment
  lengths, TIFF field counts, BMP palette sizes, ICC tag tables, EXIF thumbnail lengths, and the
  ExtendedXMP length taken from the file can no longer force a multi-gigabyte allocation or a
  `NegativeArraySizeException` from a tiny file.
- **Compressed text is bounded.** PNG `zTXt`, `iTXt`, and `iCCP` inflation stops at a fixed cap
  instead of expanding until the heap is exhausted (decompression-bomb protection).
- **The JPEG, IRB, IPTC, ICC and GIF parsers tolerate truncation.** Malformed segments and
  resource blocks are skipped instead of throwing `ArrayIndexOutOfBoundsException` and friends, and
  one bad metadata block no longer aborts reading the rest of the file.
- **Cyclic TIFF directory chains are detected** and stop, rather than looping until memory runs
  out.

Callers reading untrusted input should wrap `Metadata.readMetadata(...)` in a try/catch. As of
1.1.3 an unreadable file surfaces as an `IOException` (format rejections included), so catching
`IOException` is sufficient in normal use; catching `Exception` is still prudent as defense in
depth. See `CHANGELOG.md` for the one documented limitation (a crafted TIFF field with a very large
element count can be slow to read).

Where can I get the latest release?
-----------------------------------
This repository is a maintained fork of [dragon66/pixymeta](https://github.com/dragon66/pixymeta)
(Java 21, SLF4J 2, bug fixes; see [CHANGELOG.md](CHANGELOG.md)). The upstream project was never
released to Maven Central and its Sonatype snapshots are no longer available.

Releases of this fork are published to Maven Central:

```xml
<dependency>
  <groupId>com.akitogo</groupId>
  <artifactId>pixymeta</artifactId>
  <version>1.1.3</version>
</dependency>
```

The only runtime dependency is `org.slf4j:slf4j-api`. To build from source and install into your
local repository instead, run `mvn clean install`.

Go to the upstream [wiki] page to see this library in action.

Suggestions? Bugs? [Open] an issue on this fork.

Credits
----------------------------------------
PIXYMETA was originally written by Wen Yu ([dragon66](https://github.com/dragon66),
yuwen_66@yahoo.com) as the metadata part of the icafe project. This fork is maintained by
[akitogo](https://www.akitogo.com).

[wiki]:https://github.com/dragon66/pixymeta/wiki
[Open]:https://github.com/akitogo/pixymeta/issues/new
