/**
 * Copyright (C) 2026 by akitogo.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 */

package pixy.meta.exif.makernote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pixy.meta.MetadataEntry;

/**
 * A decoded EXIF maker note: the vendor specific directories found inside the
 * EXIF MakerNote (0x927c) tag.
 * <p>
 * Decoding is read-only. When EXIF is rewritten the maker note is carried over as the
 * opaque byte block it was read as.
 *
 * @see MakerNoteReader
 */
public class MakerNote {
	private final List<MakerNoteIFD> directories = new ArrayList<MakerNoteIFD>();

	MakerNote() {}

	void addDirectory(MakerNoteIFD directory) {
		directories.add(directory);
	}

	/**
	 * @return the decoded directories, the main maker note directory first
	 */
	public List<MakerNoteIFD> getDirectories() {
		return Collections.unmodifiableList(directories);
	}

	/**
	 * @return the main maker note directory
	 */
	public MakerNoteIFD getMainDirectory() {
		return directories.get(0);
	}

	/**
	 * @return a human readable name of the main directory, for example "Canon Makernote"
	 */
	public String getName() {
		return getMainDirectory().getName();
	}

	/**
	 * @return one MetadataEntry group per decoded directory
	 */
	public List<MetadataEntry> getMetadataEntries() {
		List<MetadataEntry> items = new ArrayList<MetadataEntry>(directories.size());
		for(MakerNoteIFD directory : directories) {
			MetadataEntry group = new MetadataEntry(directory.getName(), "Maker Note", true);
			for(MakerNoteEntry entry : directory.getEntries())
				group.addEntry(new MetadataEntry(entry.getName(), entry.getValueAsString()));
			items.add(group);
		}
		return items;
	}
}
