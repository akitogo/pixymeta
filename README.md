# pixymeta
Java image metadata CRUD tool - a pure Java image metadata manipulation tool.
This is the meta data related part branched out from icafe repository.

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
projects.

Where can I get the latest release?
-----------------------------------
This repository is a maintained fork of [dragon66/pixymeta](https://github.com/dragon66/pixymeta)
(Java 21, SLF4J 2, bug fixes; see [CHANGELOG.md](CHANGELOG.md)). The upstream project was never
released to Maven Central and its Sonatype snapshots are no longer available.

Until this fork is published, build it from source and install it into your local repository:

```bash
mvn clean install
```

Then depend on it from your pom.xml:

```xml
<dependency>
  <groupId>com.github.dragon66</groupId>
  <artifactId>pixymeta</artifactId>
  <version>1.0-SNAPSHOT</version>
</dependency>
```

The only runtime dependency is `org.slf4j:slf4j-api`.

Go to the [wiki] page to see this library in action or grab the "pixymeta.jar" from the lib folder and try it yourself!

[wiki]:https://github.com/dragon66/pixymeta/wiki
[Open]:https://github.com/dragon66/pixymeta/issues/new
Suggestions? custom requirements? [Open] an issue or send email to me directly: yuwen_66@yahoo.com
