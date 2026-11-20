/*******************************************************************************
 * Copyright (c) 2019-2022 Red Hat Inc. and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.maven.Maven;
import org.eclipse.lemminx.commons.TextDocument;
import org.eclipse.lemminx.dom.DOMDocument;
import org.eclipse.lemminx.dom.DOMNode;
import org.eclipse.lemminx.dom.DOMParser;
import org.eclipse.lemminx.extensions.maven.NoMavenCentralExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(NoMavenCentralExtension.class)
public class MavenParseUtilsTest {

	@Test
	public void testAsParentNode() {
		DOMDocument document = DOMParser.getInstance().parse(new TextDocument(
				"<blah><groupId>aGroupId</groupId><artifactId>anArtifactId</artifactId><version>aVersion</version></blah>",
				Maven.POMv4), null);
		DOMNode root = document.getRoots().iterator().next();
		assertEquals("anArtifactId", MavenParseUtils.parseArtifact(root).getArtifactId());
		assertEquals("anArtifactId", MavenParseUtils.parseArtifact(root.getChild(0)).getArtifactId());
	}
}
