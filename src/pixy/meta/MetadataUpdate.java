/**
 * Copyright (C) 2026 akitogo GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Change History - most recent changes go on top of previous changes
 *
 * MetadataUpdate.java
 *
 * Who   Date       Description
 * ====  =========  =====================================================
 * AK    09Nov2026  Initial creation
 */

package pixy.meta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import pixy.meta.exif.Exif;
import pixy.meta.iptc.IPTCDataSet;
import pixy.meta.xmp.XMP;

/**
 * Describes a set of metadata changes that {@link Metadata#insertMetadata} applies to an image in
 * one go: metadata to insert (EXIF, IPTC, XMP, ICC profile, comments) and metadata types to
 * remove. For JPEG the whole update is applied in a single pass over the file instead of one
 * read/write cycle per metadata type.
 * <p>
 * Removals are applied first, then insertions. Inserting a type that is also listed for removal
 * therefore replaces it completely, which is the same as inserting with {@code keepExisting =
 * false}. Example:
 * <pre>
 *   Exif exif = new JpegExif();
 *   exif.addField(TiffTag.ARTIST, "Jane Tester");
 *   MetadataUpdate update = new MetadataUpdate()
 *       .exif(exif, true)                                   // merge into the existing EXIF
 *       .iptc(List.of(new IPTCDataSet(IPTCApplicationTag.KEY_WORDS, "beach")), true)
 *       .xmp(xmpPacket)
 *       .remove(MetadataType.COMMENT);
 *   Metadata.insertMetadata(in, out, update);
 * </pre>
 */
public final class MetadataUpdate {
	private Exif exif;
	private boolean keepExistingExif;
	private List<IPTCDataSet> iptc;
	private boolean keepExistingIptc;
	private XMP xmp;
	private String xmpString;
	private byte[] iccProfile;
	private List<String> comments;
	private final Set<MetadataType> removals = EnumSet.noneOf(MetadataType.class);

	public MetadataUpdate() {}

	/**
	 * EXIF to insert. With {@code keepExisting} the fields are merged into the image's existing
	 * EXIF (new fields replace old ones of the same tag, everything else, including the maker note
	 * and thumbnail, is kept); without it the existing EXIF is replaced.
	 */
	public MetadataUpdate exif(Exif exif, boolean keepExisting) {
		this.exif = exif;
		this.keepExistingExif = keepExisting;
		return this;
	}

	/**
	 * IPTC data sets to insert. With {@code keepExisting} they are merged into the image's
	 * existing IPTC (a new data set replaces the old data sets of the same non-repeatable tag;
	 * repeatable tags such as Keywords are appended); without it the existing IPTC is replaced.
	 */
	public MetadataUpdate iptc(Collection<? extends IPTCDataSet> dataSets, boolean keepExisting) {
		this.iptc = dataSets == null ? null : new ArrayList<IPTCDataSet>(dataSets);
		this.keepExistingIptc = keepExisting;
		return this;
	}

	/** XMP packet to insert; an existing packet is replaced. The string must be UTF-8 XML. */
	public MetadataUpdate xmp(String xmp) {
		this.xmpString = xmp;
		this.xmp = null;
		return this;
	}

	/**
	 * XMP to insert; an existing packet is replaced. For JPEG the instance must be a
	 * {@link pixy.meta.jpeg.JpegXMP}, as for {@link Metadata#insertXMP(java.io.InputStream, java.io.OutputStream, XMP)}.
	 */
	public MetadataUpdate xmp(XMP xmp) {
		this.xmp = xmp;
		this.xmpString = null;
		return this;
	}

	/** ICC profile to insert; an existing profile is replaced. */
	public MetadataUpdate iccProfile(byte[] iccProfile) {
		this.iccProfile = iccProfile;
		return this;
	}

	/** Comments to add (formats that support comments only). */
	public MetadataUpdate comments(List<String> comments) {
		this.comments = comments == null ? null : new ArrayList<String>(comments);
		return this;
	}

	/** Adds one comment; see {@link #comments(List)}. */
	public MetadataUpdate comment(String comment) {
		if(comments == null) comments = new ArrayList<String>();
		comments.add(comment);
		return this;
	}

	/** Metadata types to remove, as for {@link Metadata#removeMetadata}. */
	public MetadataUpdate remove(MetadataType... types) {
		removals.addAll(Arrays.asList(types));
		return this;
	}

	public Exif getExif() { return exif; }
	public boolean keepExistingExif() { return keepExistingExif; }
	public List<IPTCDataSet> getIptc() { return iptc == null ? null : Collections.unmodifiableList(iptc); }
	public boolean keepExistingIptc() { return keepExistingIptc; }
	public XMP getXmp() { return xmp; }
	public String getXmpString() { return xmpString; }
	public boolean hasXmp() { return xmp != null || xmpString != null; }
	public byte[] getIccProfile() { return iccProfile; }
	public List<String> getComments() { return comments == null ? null : Collections.unmodifiableList(comments); }
	public Set<MetadataType> getRemovals() { return Collections.unmodifiableSet(removals); }
	public boolean removes(MetadataType type) { return removals.contains(type); }

	/** True if the update neither inserts nor removes anything. */
	public boolean isEmpty() {
		return exif == null && iptc == null && !hasXmp() && iccProfile == null
				&& (comments == null || comments.isEmpty()) && removals.isEmpty();
	}
}
