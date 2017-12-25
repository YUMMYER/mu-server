package ronin.muserver.handlers;

import ronin.muserver.HeaderNames;
import ronin.muserver.MuHandler;
import ronin.muserver.MuRequest;
import ronin.muserver.MuResponse;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

import static ronin.muserver.handlers.ResourceType.DEFAULT_EXTENSION_MAPPINGS;

public class ResourceHandler implements MuHandler {
    private final Map<String, ResourceType> extensionToResourceType;
    private final String pathToServeFrom;
    private final String defaultFile;
    private final ResourceProviderFactory resourceProviderFactory;

    public ResourceHandler(ResourceProviderFactory resourceProviderFactory, String pathToServeFrom, String defaultFile, Map<String, ResourceType> extensionToResourceType) {
        this.resourceProviderFactory = resourceProviderFactory;
        this.pathToServeFrom = pathToServeFrom;
        this.extensionToResourceType = extensionToResourceType;
        this.defaultFile = defaultFile;
    }

    @Override
    public boolean handle(MuRequest request, MuResponse response) throws Exception {
        String requestPath = request.uri().getPath();
        if (!requestPath.startsWith(pathToServeFrom)) {
            return false;
        }
        if (requestPath.endsWith("/") && defaultFile != null) {
            requestPath += defaultFile;
        }

        String pathWithoutWebPrefix = pathToServeFrom.equals("/")
            ? "." + requestPath
            : "." + requestPath.substring(pathToServeFrom.length());

        ResourceProvider provider = resourceProviderFactory.get(pathWithoutWebPrefix);
        if (!provider.exists()) {
            System.out.println("Could not find " + requestPath);
            return false;
        }
        Long fileSize = provider.fileSize();
        if (fileSize != null) {
            response.headers().add(HeaderNames.CONTENT_LENGTH, fileSize);
        }

        String filename = requestPath.substring(requestPath.lastIndexOf('/'));
        addHeaders(response, filename);

        try (OutputStream out = response.outputStream(16 * 1024)) {
            provider.writeTo(out);
        }

        return true;
    }

    private void addHeaders(MuResponse response, String fileName) {
        int ind = fileName.lastIndexOf('.');
        ResourceType type;
        if (ind == -1) {
            type = ResourceType.DEFAULT;
        } else {
            String extension = fileName.substring(ind + 1).toLowerCase();
            type = extensionToResourceType.getOrDefault(extension, ResourceType.DEFAULT);
        }
        response.headers().set(HeaderNames.CONTENT_TYPE, type.mimeType);
        response.headers().add(type.headers);
    }

    public static class Builder {
        private Map<String, ResourceType> extensionToResourceType = DEFAULT_EXTENSION_MAPPINGS;
        private String pathToServeFrom = "/";
        private String defaultFile = "index.html";
        private ResourceProviderFactory resourceProviderFactory;

        public Builder withExtensionToResourceType(Map<String, ResourceType> extensionToResourceType) {
            this.extensionToResourceType = extensionToResourceType;
            return this;
        }

        public Builder withPathToServeFrom(String pathToServeFrom) {
            this.pathToServeFrom = pathToServeFrom;
            return this;
        }

        public Builder withDefaultFile(String defaultFile) {
            this.defaultFile = defaultFile;
            return this;
        }

        public Builder withResourceProviderFactory(ResourceProviderFactory resourceProviderFactory) {
            this.resourceProviderFactory = resourceProviderFactory;
            return this;
        }

        public ResourceHandler build() {
            if (resourceProviderFactory == null) {
                throw new IllegalStateException("No resourceProviderFactory has been set");
            }
            return new ResourceHandler(resourceProviderFactory, pathToServeFrom, defaultFile, extensionToResourceType);
        }
    }

    public static ResourceHandler.Builder fileHandler(String directoryPath) {
        return fileHandler(Paths.get(directoryPath));
    }

    public static ResourceHandler.Builder fileHandler(File baseDirectory) {
        return fileHandler(baseDirectory.toPath());
    }

    public static ResourceHandler.Builder fileHandler(Path path) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.fileBased(path));
    }

    public static ResourceHandler.Builder classpathHandler(String classpathRoot) {
        return new Builder().withResourceProviderFactory(ResourceProviderFactory.classpathBased(classpathRoot));
    }

    public static ResourceHandler.Builder resourceHandler(String fileRootIfExists, String classpathRoot) {
        Path path = Paths.get(fileRootIfExists);
        if (Files.isDirectory(path)) {
            return fileHandler(path);
        } else {
            return classpathHandler(classpathRoot);
        }
    }

}
