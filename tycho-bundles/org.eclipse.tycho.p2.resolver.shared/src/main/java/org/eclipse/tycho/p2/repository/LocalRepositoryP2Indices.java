/*******************************************************************************
 * Copyright (c) 2011 SAP AG and others.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *     SAP AG - initial API and implementation
 *******************************************************************************/

package org.eclipse.tycho.p2.repository;

import java.io.File;

import org.eclipse.tycho.core.shared.MavenContext;

/**
 * This service provides access to the tycho p2 index files of the local maven repository.
 */
public interface LocalRepositoryP2Indices {

    public TychoRepositoryIndex getArtifactsIndex();

    public TychoRepositoryIndex getMetadataIndex();

    public File getBasedir();

    MavenContext getMavenContext();

}
