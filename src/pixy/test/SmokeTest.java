package pixy.test;

import java.util.Map;

import pixy.meta.Metadata;
import pixy.meta.MetadataType;

/**
 * Minimal regression-check harness for the "Unknown IPTC record" fix.
 *
 * Usage:
 *   java -cp pixymeta.jar:<deps> pixy.test.SmokeTest <path-to-jpeg>
 *
 * Prints "OK — N metadata entries" plus the entry types if Metadata.readMetadata
 * completes without throwing. Prints "THROWN: ..." with the exception class and
 * message if anything blows up — useful when a production-flagged file is dropped
 * in tmp/ to verify the IPTCDataSet.getTagName fallback still holds.
 *
 * Unlike TestPixyMeta, this writes to System.out (not SLF4J) so the result is
 * visible without configuring a logging backend on the classpath.
 */
public class SmokeTest {
	public static void main(String[] args) {
		if (args.length == 0) {
			System.err.println("usage: SmokeTest <jpeg>");
			System.exit(2);
		}
		System.out.println("Reading: " + args[0]);
		try {
			Map<MetadataType, Metadata> m = Metadata.readMetadata(args[0]);
			System.out.println("OK — " + m.size() + " metadata entries");
			for (MetadataType t : m.keySet()) System.out.println("  - " + t);
		} catch (Throwable t) {
			System.out.println("THROWN: " + t.getClass().getName() + ": " + t.getMessage());
			t.printStackTrace(System.out);
			System.exit(1);
		}
	}
}
