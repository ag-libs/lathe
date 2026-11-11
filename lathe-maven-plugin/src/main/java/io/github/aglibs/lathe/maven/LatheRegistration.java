package io.github.aglibs.lathe.maven;

import io.github.aglibs.lathe.core.LatheLayout;
import io.github.aglibs.lathe.install.SyncException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.IntStream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.maven.project.MavenProject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

// Whether the project registers lathe-maven-extension itself: in .mvn/extensions.xml, or as a
// build extension of the root POM, inherited ones included. A build started with Lathe on
// -Dmaven.ext.class.path cannot tell from inside: that copy wins over the project's, whose pin it
// would then override in every sync that replays it.
final class LatheRegistration {

  static final String EXTENSIONS_FILE = "extensions.xml";
  private static final String EXTENSION_ELEMENT = "extension";
  private static final String GROUP_ID_ELEMENT = "groupId";
  private static final String ARTIFACT_ID_ELEMENT = "artifactId";
  private static final String DISALLOW_DOCTYPE =
      "http://apache.org/xml/features/disallow-doctype-decl";

  private LatheRegistration() {}

  static boolean registersLathe(final Path workspaceRoot, final MavenProject rootProject) {
    return rootProject.getBuildExtensions().stream()
            .anyMatch(extension -> isLathe(extension.getGroupId(), extension.getArtifactId()))
        || inCoreExtensions(workspaceRoot.resolve(MavenJvmArgs.MVN_DIR).resolve(EXTENSIONS_FILE));
  }

  private static boolean inCoreExtensions(final Path file) {
    if (!Files.isRegularFile(file)) {
      return false;
    }

    final NodeList extensions;
    try {
      extensions = parse(file).getDocumentElement().getElementsByTagName(EXTENSION_ELEMENT);
    } catch (final IOException | ParserConfigurationException | SAXException e) {
      throw new SyncException("lathe:sync failed to read %s".formatted(file), e);
    }

    return IntStream.range(0, extensions.getLength())
        .mapToObj(index -> (Element) extensions.item(index))
        .anyMatch(LatheRegistration::isLatheExtension);
  }

  // The JDK's parser: Maven 3.9 gives plugins Xpp3Dom but not its builder. No DTDs, as for any
  // XML read from a project.
  private static Document parse(final Path file)
      throws IOException, ParserConfigurationException, SAXException {
    final var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature(DISALLOW_DOCTYPE, true);
    try (final var input = Files.newInputStream(file)) {
      return factory.newDocumentBuilder().parse(input);
    }
  }

  private static boolean isLatheExtension(final Element extension) {
    return isLathe(text(extension, GROUP_ID_ELEMENT), text(extension, ARTIFACT_ID_ELEMENT));
  }

  private static String text(final Element parent, final String name) {
    final NodeList children = parent.getElementsByTagName(name);
    return children.getLength() == 0 ? "" : children.item(0).getTextContent().trim();
  }

  private static boolean isLathe(final String groupId, final String artifactId) {
    return LatheLayout.Maven.LATHE_GROUP_ID.equals(groupId)
        && LatheLayout.Maven.EXTENSION_ARTIFACT_ID.equals(artifactId);
  }
}
