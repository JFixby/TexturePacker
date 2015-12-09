/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.regex.Pattern;

import com.badlogic.gdx.utils.Array;
import com.jfixby.cmns.api.collections.Collection;
import com.jfixby.cmns.api.collections.JUtils;
import com.jfixby.cmns.api.debug.Debug;
import com.jfixby.cmns.api.file.File;

/**
 * Collects files recursively, filtering by file name. Callbacks are provided to
 * process files and the results are collected, either
 * {@link #processFile(Entry)} or {@link #processDir(Entry, ArrayList)} can be
 * overridden, or both. The entries provided to the callbacks have the original
 * file, the output directory, and the output file. If
 * {@link #setFlattenOutput(boolean)} is false, the output will match the
 * directory structure of the input.
 * 
 * @author Nathan Sweet
 */
public class FileProcessor {
	FilenameFilter inputFilter;
	Comparator<File> comparator = new Comparator<File>() {
		public int compare(File o1, File o2) {
			return o1.getName().compareTo(o2.getName());
		}
	};
	Array<Pattern> inputRegex = new Array();
	String outputSuffix;
	ArrayList<Entry> outputFiles = new ArrayList();
	boolean recursive = true;
	final boolean flattenOutput = true;

	Comparator<Entry> entryComparator = new Comparator<Entry>() {
		public int compare(Entry o1, Entry o2) {
			return comparator.compare(o1.inputFile, o2.inputFile);
		}
	};

	public FileProcessor() {
	}

	public FileProcessor setInputFilter(FilenameFilter inputFilter) {
		this.inputFilter = inputFilter;
		return this;
	}

	/**
	 * Sets the comparator for {@link #processDir(Entry, ArrayList)}. By default
	 * the files are sorted by alpha.
	 */
	public FileProcessor setComparator(Comparator<File> comparator) {
		this.comparator = comparator;
		return this;
	}

	/** Adds a case insensitive suffix for matching input files. */
	public FileProcessor addInputSuffix(String... suffixes) {
		for (String suffix : suffixes)
			addInputRegex("(?i).*" + Pattern.quote(suffix));
		return this;
	}

	public FileProcessor addInputRegex(String... regexes) {
		for (String regex : regexes)
			inputRegex.add(Pattern.compile(regex));
		return this;
	}

	/**
	 * Sets the suffix for output files, replacing the extension of the input
	 * file.
	 */
	public FileProcessor setOutputSuffix(String outputSuffix) {
		this.outputSuffix = outputSuffix;
		return this;
	}

	/** Default is true. */
	public FileProcessor setRecursive(boolean recursive) {
		this.recursive = recursive;
		return this;
	}

	/**
	 * @param outputRoot
	 *            May be null.
	 * @see #process(File, File)
	 */
	public ArrayList<Entry> process(String inputFile, String outputRoot) throws Exception {
		return process(FileWrapper.file(inputFile), outputRoot == null ? null : FileWrapper.file(outputRoot));
	}

	/**
	 * Processes the specified input file or directory.
	 * 
	 * @param outputRoot
	 *            May be null if there is no output from processing the files.
	 * @return the processed files added with {@link #addProcessedFile(Entry)}.
	 */
	public ArrayList<Entry> process(File inputFile, File outputRoot) throws Exception {
		if (!inputFile.exists())
			throw new IllegalArgumentException("Input file does not exist: " + inputFile.toJavaFile().getAbsolutePath());
		if (inputFile.isFile())
			return process(JUtils.newList(inputFile), outputRoot);
		else
			return process(inputFile.listChildren(), outputRoot);
	}

	/**
	 * Processes the specified input files.
	 * 
	 * @param outputRoot
	 *            May be null if there is no output from processing the files.
	 * @return the processed files added with {@link #addProcessedFile(Entry)}.
	 */
	public ArrayList<Entry> process(Collection<File> files, File outputRoot) throws Exception {

		// files.print("processing files");
		// L.d("outputRoot", outputRoot);

		Debug.checkNull("outputRoot", outputRoot);
		outputFiles.clear();
		DirToEntries dirToEntries = new DirToEntries();
		process(files, outputRoot, outputRoot, dirToEntries, 0);
		// dirToEntries.print();

		ArrayList<Entry> allEntries = new ArrayList();
		for (int i = 0; i < dirToEntries.size(); i++) {
			File inputDir = dirToEntries.getKey(i);
			ArrayList<Entry> dirEntries = dirToEntries.getValue(i);
			if (comparator != null)
				Collections.sort(dirEntries, entryComparator);

			File newOutputDir = null;
			if (flattenOutput)
				newOutputDir = outputRoot;
			else if (!dirEntries.isEmpty()) //
				newOutputDir = dirEntries.get(0).outputDir;
			String outputName = inputDir.getName();
			if (outputSuffix != null)
				outputName = outputName.replaceAll("(.*)\\..*", "$1") + outputSuffix;

			Entry entry = new Entry();
			entry.inputFile = inputDir;
			entry.outputDir = newOutputDir;

			if (newOutputDir != null) {
				// File v1 = F.file(outputName);
				File v2 = FileWrapper.file(newOutputDir, outputName);
				entry.outputFile = v2;
			}
			try {
				processDir(entry, dirEntries);
			} catch (Exception ex) {
				throw new Exception("Error processing directory: " + entry.inputFile.toJavaFile().getAbsolutePath(), ex);
			}
			allEntries.addAll(dirEntries);
		}

		if (comparator != null)
			Collections.sort(allEntries, entryComparator);
		for (Entry entry : allEntries) {
			try {
				processFile(entry);
			} catch (Exception ex) {
				throw new Exception("Error processing file: " + entry.inputFile.toJavaFile().getAbsolutePath(), ex);
			}
		}

		return outputFiles;
	}

	private void process(Collection<File> files, File outputRoot, File outputDir, DirToEntries dirToEntries, int depth) {
		// Store empty entries for every directory.
		for (File file : files) {
			File dir = file.parent();
			ArrayList<Entry> entries = dirToEntries.get(dir);
			if (entries == null) {
				entries = new ArrayList();
				dirToEntries.put(dir, entries);
			}
		}

		for (File file : files) {
			if (file.isFile()) {
				if (inputRegex.size > 0) {
					boolean found = false;
					for (Pattern pattern : inputRegex) {
						if (pattern.matcher(file.getName()).matches()) {
							found = true;
							continue;
						}
					}
					if (!found)
						continue;
				}

				File dir = file.parent();
				if (inputFilter != null && !inputFilter.fits(file))
					continue;

				String outputName = file.getName();
				if (outputSuffix != null)
					outputName = outputName.replaceAll("(.*)\\..*", "$1") + outputSuffix;

				Entry entry = new Entry();
				entry.depth = depth;
				entry.inputFile = file;
				entry.outputDir = outputDir;

				if (flattenOutput) {
					entry.outputFile = FileWrapper.file(outputRoot, outputName);
				} else {
					entry.outputFile = FileWrapper.file(outputDir, outputName);
				}

				dirToEntries.get(dir).add(entry);
			}
			if (recursive && file.isFolder()) {
				File subdir = outputDir.toJavaFile().getPath().length() == 0 ? FileWrapper.file(file.getName()) : FileWrapper.file(outputDir, file.getName());
				process(file.listChildren().filter(inputFilter), outputRoot, subdir, dirToEntries, depth + 1);
			}
		}
	}

	/** Called with each input file. */
	protected void processFile(Entry entry) throws Exception {
	}

	/**
	 * Called for each input directory. The files will be
	 * {@link #setComparator(Comparator) sorted}.
	 */
	protected void processDir(Entry entryDir, ArrayList<Entry> files) throws Exception {
	}

	/**
	 * This method should be called by {@link #processFile(Entry)} or
	 * {@link #processDir(Entry, ArrayList)} if the return value of
	 * {@link #process(File, File)} or {@link #process(File[], File)} should
	 * return all the processed files.
	 */
	protected void addProcessedFile(Entry entry) {
		outputFiles.add(entry);
	}
}
