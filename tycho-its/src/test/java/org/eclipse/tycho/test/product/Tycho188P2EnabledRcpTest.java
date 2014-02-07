/*******************************************************************************
 * Copyright (c) 2010, 2014 SAP AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     SAP AG - initial API and implementation
 *******************************************************************************/
package org.eclipse.tycho.test.product;

import static org.eclipse.tycho.test.util.TychoMatchers.isFile;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.it.Verifier;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.tycho.core.facade.TargetEnvironment;
import org.eclipse.tycho.test.AbstractTychoIntegrationTest;
import org.eclipse.tycho.test.util.ArchiveContentUtil;
import org.eclipse.tycho.test.util.EclipseInstallationTool;
import org.eclipse.tycho.test.util.P2RepositoryTool;
import org.eclipse.tycho.test.util.P2RepositoryTool.IU;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.theories.DataPoints;
import org.junit.experimental.theories.Theories;
import org.junit.experimental.theories.Theory;
import org.junit.runner.RunWith;

@RunWith(Theories.class)
public class Tycho188P2EnabledRcpTest extends AbstractTychoIntegrationTest {

    private static final String MODULE = "eclipse-repository";
    private static final String GROUP_ID = "tycho-its-project.product.installation";
    private static final String ARTIFACT_ID = "pi.eclipse-repository";
    private static final String VERSION = "1.0.0-SNAPSHOT";

    private static final List<Product> TEST_PRODUCTS = Arrays.asList(new Product("main.product.id", "", false, true),
            new Product("extra.product.id", "extra", "rootfolder", true, false), new Product("repoonly.product.id",
                    false));

    @DataPoints
    public static final TargetEnvironment[] TEST_ENVIRONMENTS = new TargetEnvironment[] {
            new TargetEnvironment("win32", "win32", "x86"), new TargetEnvironment("linux", "gtk", "x86") };

    private static Verifier verifier;

    @BeforeClass
    public static void buildProduct() throws Exception {
        verifier = new Tycho188P2EnabledRcpTest().getVerifier("product.installation", false);

        verifier.executeGoal("install");
        verifier.verifyErrorFreeLog();
    }

    @Test
    public void testInstalledProdcutArtifacts() throws Exception {
        for (Product product : TEST_PRODUCTS) {
            for (TargetEnvironment env : TEST_ENVIRONMENTS) {
                assertProductArtifacts(verifier, product, env);
            }
        }
    }

    @Test
    public void testPublishedProducts() throws Exception {
        P2RepositoryTool p2Repository = P2RepositoryTool.forEclipseRepositoryModule(new File(verifier.getBasedir(),
                MODULE));

        for (Product product : TEST_PRODUCTS) {
            for (TargetEnvironment env : TEST_ENVIRONMENTS) {
                assertProductIUs(p2Repository, product, env);
            }
        }
    }

    @Test
    public void testContent() throws Exception {
        assertRepositoryArtifacts(verifier);
        int environmentsPerProduct = TEST_ENVIRONMENTS.length;

        int publishedArtifacts = TEST_PRODUCTS.size() * environmentsPerProduct; // the branded executables, produced by the ProductAction

        int materializedProducts = TEST_PRODUCTS.size() - 1;
        int distributionArtifacts = materializedProducts * environmentsPerProduct;

        int repositoryArtifacts = 1;
        assertTotalZipArtifacts(verifier, publishedArtifacts + distributionArtifacts + repositoryArtifacts);
    }

    @Test
    public void testRootLevelFeaturesAreIncludedInP2Repository() throws Exception {
        P2RepositoryTool p2Repository = P2RepositoryTool.forEclipseRepositoryModule(new File(verifier.getBasedir(),
                MODULE));

        // test that root level feature is assembled into the p2 repository...
        File rootFeatureInRepo = p2Repository.findFeatureArtifact("pi.root-level-installed-feature");
        assertThat(rootFeatureInRepo, isFile());

        // ... although there is no dependency from the product IU.
        assertThat(p2Repository.getUniqueIU("main.product.id").getRequiredIds(),
                not(hasItem("pi.root-level-installed-feature.feature.group")));
    }

    @Test
    public void testRootLevelFeaturesAreInstalledAtRoot() throws Exception {
        // indirectly test that the features are installed as separate roots (from the p2 director output); we currently don't have code to read the p2 installation profile
        verifier.verifyTextInLog("Installing pi.root-level-installed-feature");
    }

    @Theory
    public void testRootLevelFeaturesAreInstalledInRightProducts(TargetEnvironment env) {
        File basedir = new File(verifier.getBasedir(), MODULE);

        EclipseInstallationTool rootFeatureProduct = EclipseInstallationTool.forInstallationInEclipseRepositoryTarget(
                basedir, "main.product.id", env, null);
        EclipseInstallationTool otherProduct = EclipseInstallationTool.forInstallationInEclipseRepositoryTarget(
                basedir, "extra.product.id", env, "rootfolder");

        assertThat(rootFeatureProduct.getInstalledFeatureIds(), hasItem("pi.root-level-installed-feature"));
        assertThat(otherProduct.getInstalledFeatureIds(), not(hasItem("pi.root-level-installed-feature")));
    }

    static private void assertProductIUs(P2RepositoryTool p2Repository, Product product, TargetEnvironment env)
            throws Exception {
        IU productIU = p2Repository.getUniqueIU(product.unitId);
        assertThat(productIU.getProperties(), hasItem("org.eclipse.equinox.p2.type.product=true"));
        if (product.p2InfProperty) {
            assertThat(productIU.getProperties(), hasItem("p2.inf.added-property=true"));
        } else {
            assertThat(productIU.getProperties(), not(hasItem("p2.inf.added-property=true")));
        }

        /*
         * This only works if the context repositories are configured correctly. If the
         * simpleconfigurator bundle is not visible to the product publisher, this IU would not be
         * generated.
         */
        String simpleConfiguratorIU = "tooling" + env.toConfigSpec() + "org.eclipse.equinox.simpleconfigurator";
        assertThat(p2Repository.getAllUnitIds(), hasItem(simpleConfiguratorIU));
    }

    static private void assertProductArtifacts(Verifier verifier, Product product, TargetEnvironment env)
            throws Exception {
        if (product.isMaterialized()) {
            File artifactDirectory = new File(verifier.getArtifactPath(GROUP_ID, ARTIFACT_ID, VERSION, "zip"))
                    .getParentFile();
            File installedProductArchive = new File(artifactDirectory, ARTIFACT_ID + '-' + VERSION
                    + product.getAttachIdSegment() + "-" + toOsWsArch(env) + ".zip");
            assertTrue("Product archive not found at: " + installedProductArchive, installedProductArchive.exists());

            String rootFolder = product.getRootFolderName() != null ? product.getRootFolderName() + "/" : "";

            Properties configIni = openPropertiesFromZip(installedProductArchive, rootFolder
                    + "configuration/config.ini");
            String bundleConfiguration = configIni.getProperty("osgi.bundles");
            assertTrue("Installation is not configured to use the simpleconfigurator",
                    bundleConfiguration.startsWith("reference:file:org.eclipse.equinox.simpleconfigurator"));
            // TODO all these assertions should be in the test method directly
            String expectedProfileName = env.getOs().equals("linux") ? "ProfileNameForLinux"
                    : "ConfiguredDefaultProfileName";
            assertEquals("eclipse.p2.profile in config.ini", expectedProfileName,
                    configIni.getProperty("eclipse.p2.profile"));

            assertRootFolder(installedProductArchive, product.getRootFolderName());

            if (product.hasLocalFeature()) {
                assertContainsEntry(installedProductArchive, rootFolder + "features/pi.example.feature_1.0.0.");
                assertContainsEntry(installedProductArchive, rootFolder + "plugins/pi.example.bundle_1.0.0.");
            }
        }
    }

    private static String toOsWsArch(TargetEnvironment env) {
        return env.getOs() + '.' + env.getWs() + '.' + env.getArch();
    }

    private static void assertContainsEntry(File file, String prefix) throws Exception {
        for (String archiveFile : ArchiveContentUtil.getFilesInZip(file)) {
            if (archiveFile.startsWith(prefix)) {
                assertThat(archiveFile, not(endsWith("qualifier")));
            }
        }
    }

    private static void assertRootFolder(File file, String rootFolderName) throws Exception {
        if (rootFolderName != null) {
            Set<String> archiveFiles = ArchiveContentUtil.getFilesInZip(file);
            assertThat(archiveFiles, hasItem(rootFolderName + "/"));
            assertThat(archiveFiles, hasItem(rootFolderName + "/configuration/config.ini"));
        }
    }

    static private void assertRepositoryArtifacts(Verifier verifier) {
        verifier.assertArtifactPresent(GROUP_ID, ARTIFACT_ID, VERSION, "zip");
    }

    static private void assertTotalZipArtifacts(final Verifier verifier, final int expectedArtifacts) {
        final File artifactDirectory = new File(verifier.getArtifactPath(GROUP_ID, ARTIFACT_ID, VERSION, "zip"))
                .getParentFile();
        final String prefix = ARTIFACT_ID + '-' + VERSION;

        int zipArtifacts = 0;
        for (final String fileName : artifactDirectory.list()) {
            if (fileName.startsWith(prefix) && fileName.endsWith(".zip")) {
                zipArtifacts++;
            }
        }
        assertEquals(expectedArtifacts, zipArtifacts);
    }

    public static Properties openPropertiesFromZip(File zipFile, String propertyFile) throws Exception {
        Properties configIni = new Properties();
        configIni.load(new ByteArrayInputStream(IOUtil.toByteArray(ArchiveContentUtil.getFileContent(zipFile,
                propertyFile))));
        return configIni;
    }

    static class Product {
        String unitId;

        String attachId;

        boolean p2InfProperty;

        private final boolean localFeature;

        private final String rootFolderName;

        Product(String unitId, String attachId, String rootFolderName, boolean p2InfProperty, boolean localFeature) {
            this.unitId = unitId;
            this.attachId = attachId;
            this.p2InfProperty = p2InfProperty;
            this.localFeature = localFeature;
            this.rootFolderName = rootFolderName;
        }

        Product(String unitId, String attachId, boolean p2InfProperty, boolean localFeature) {
            this(unitId, attachId, null, p2InfProperty, localFeature);
        }

        Product(String unitId, boolean p2InfProperty) {
            this(unitId, null, null, p2InfProperty, false);
        }

        boolean isMaterialized() {
            return attachId != null;
        }

        String getAttachIdSegment() {
            if (attachId == null) {
                throw new IllegalStateException();
            }
            return attachId.length() == 0 ? "" : "-" + attachId;
        }

        boolean hasLocalFeature() {
            return localFeature;
        }

        public String getRootFolderName() {
            return rootFolderName;
        }
    }
}
