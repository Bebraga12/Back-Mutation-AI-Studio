package com.mutation.mutation_ai_studio.adapters.in.web.project;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.nio.file.Files;
import java.nio.file.Path;

@Service
public class PitestPomPluginInstaller {

    private static final String PITEST_GROUP_ID = "org.pitest";
    private static final String PITEST_ARTIFACT_ID = "pitest-maven";
    private static final String PITEST_VERSION = "1.17.3";
    private static final String PITEST_JUNIT5_ARTIFACT_ID = "pitest-junit5-plugin";
    private static final String PITEST_JUNIT5_VERSION = "1.2.1";

    public void ensurePluginInstalled(String repositoryPath) {
        Path pomPath = Path.of(repositoryPath).toAbsolutePath().normalize().resolve("pom.xml");
        if (!Files.exists(pomPath) || !Files.isRegularFile(pomPath)) {
            throw new IllegalArgumentException("Projeto Maven invalido: pom.xml nao encontrado em " + pomPath.getParent());
        }

        try {
            Document document = parseDocument(pomPath);
            Element project = document.getDocumentElement();
            Element build = ensureChild(document, project, "build");
            Element plugins = ensureChild(document, build, "plugins");

            if (!hasPitestPlugin(plugins)) {
                plugins.appendChild(createPitestPlugin(document));
                writeDocument(document, pomPath);
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Falha ao atualizar pom.xml do projeto: " + exception.getMessage(), exception);
        }
    }

    private Document parseDocument(Path pomPath) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(pomPath.toFile());
    }

    private void writeDocument(Document document, Path pomPath) throws Exception {
        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);

        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        transformer.transform(new DOMSource(document), new StreamResult(pomPath.toFile()));
    }

    private boolean hasPitestPlugin(Element plugins) {
        NodeList pluginNodes = plugins.getElementsByTagName("plugin");
        for (int index = 0; index < pluginNodes.getLength(); index++) {
            Element plugin = (Element) pluginNodes.item(index);
            String groupId = childText(plugin, "groupId");
            String artifactId = childText(plugin, "artifactId");
            if (PITEST_GROUP_ID.equals(groupId) && PITEST_ARTIFACT_ID.equals(artifactId)) {
                return true;
            }
        }
        return false;
    }

    private Element createPitestPlugin(Document document) {
        Element plugin = document.createElement("plugin");
        plugin.appendChild(createTextElement(document, "groupId", PITEST_GROUP_ID));
        plugin.appendChild(createTextElement(document, "artifactId", PITEST_ARTIFACT_ID));
        plugin.appendChild(createTextElement(document, "version", PITEST_VERSION));

        Element dependencies = document.createElement("dependencies");
        Element dependency = document.createElement("dependency");
        dependency.appendChild(createTextElement(document, "groupId", PITEST_GROUP_ID));
        dependency.appendChild(createTextElement(document, "artifactId", PITEST_JUNIT5_ARTIFACT_ID));
        dependency.appendChild(createTextElement(document, "version", PITEST_JUNIT5_VERSION));
        dependencies.appendChild(dependency);
        plugin.appendChild(dependencies);

        Element configuration = document.createElement("configuration");
        Element outputFormats = document.createElement("outputFormats");
        outputFormats.appendChild(createTextElement(document, "param", "XML"));
        outputFormats.appendChild(createTextElement(document, "param", "HTML"));
        configuration.appendChild(outputFormats);
        plugin.appendChild(configuration);

        return plugin;
    }

    private Element ensureChild(Document document, Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index).getParentNode() == parent) {
                return (Element) nodes.item(index);
            }
        }

        Element child = document.createElement(tagName);
        parent.appendChild(child);
        return child;
    }

    private Element createTextElement(Document document, String tagName, String value) {
        Element element = document.createElement(tagName);
        element.setTextContent(value);
        return element;
    }

    private String childText(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        for (int index = 0; index < nodes.getLength(); index++) {
            if (nodes.item(index).getParentNode() == parent) {
                return nodes.item(index).getTextContent().trim();
            }
        }
        return "";
    }
}
