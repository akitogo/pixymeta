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

/**
 * One decoded maker note directory, e.g. the main Canon directory or the Olympus
 * "Camera Settings" sub directory.
 */
public class MakerNoteIFD {
	private final MakerNoteDirectory directory;
	private final List<MakerNoteEntry> entries = new ArrayList<MakerNoteEntry>();

	MakerNoteIFD(MakerNoteDirectory directory) {
		this.directory = directory;
	}

	void addEntry(MakerNoteEntry entry) {
		entries.add(entry);
	}

	public MakerNoteDirectory getDirectory() {
		return directory;
	}

	public String getName() {
		return directory.getName();
	}

	/**
	 * @return the entries in the order they appear in the maker note
	 */
	public List<MakerNoteEntry> getEntries() {
		return Collections.unmodifiableList(entries);
	}

	/**
	 * @param tag maker note tag id
	 * @return the first entry with this tag, or null
	 */
	public MakerNoteEntry getEntry(int tag) {
		for(MakerNoteEntry entry : entries) {
			if(entry.getTag() == tag) return entry;
		}
		return null;
	}
}
