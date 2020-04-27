/*******************************************************************************
 * Copyright (c) 2019-2020 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.maven.test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.services.XMLLanguageService;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.TextDocumentItem;

public interface MavenLemminxTestsUtils {
	
	public static TextDocumentItem createTextDocumentItem(String resourcePath) throws IOException, URISyntaxException {
		return createTextDocumentItem(resourcePath, null);
	}
	
	public static DOMDocument createDOMDocument(String resourcePath, XMLLanguageService languageService) throws IOException, URISyntaxException {
		return org.eclipse.lemminx.dom.DOMParser.getInstance().parse(new TextDocument(createTextDocumentItem(resourcePath)), languageService.getResolverExtensionManager());
	}

	public static TextDocumentItem createTextDocumentItem(String resourcePath, Map<String, String> replacements) throws IOException, URISyntaxException {
		URI uri = MavenLemminxTestsUtils.class.getResource(resourcePath).toURI();
		File file = new File(uri);
		String contents = new String(Files.readAllBytes(file.toPath()));
		if (replacements != null) {
			replacements.forEach((key, value) -> contents.replaceAll(key, value));
		}
		return new TextDocumentItem(uri.toString(), "xml", 1, contents);
	}

	public static boolean completionContains(List<CompletionItem> completionItems, String searchString) {
		return completionItems.stream().map(CompletionItem::getLabel).anyMatch(label -> label.contains(searchString));
	}

}
