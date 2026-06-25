package ai.myrmec.engine.knowledge.rag;

import java.io.IOException;

/**
 * Creates an {@link ObjectStore} for one sync from a source's connection
 * settings. The production implementation ({@link MinioObjectStoreFactory})
 * builds an S3-protocol client; tests provide an in-memory factory so
 * {@link S3Connector} can be exercised hermetically.
 */
interface ObjectStoreFactory {

    /**
     * Connection settings for an S3-protocol endpoint.
     *
     * @param endpoint   service endpoint URL (e.g. {@code https://s3.amazonaws.com}
     *                   or a region/compatible endpoint). Required.
     * @param region     region id (may be {@code null} for endpoints that don't
     *                   need it).
     * @param accessKey  access key id (may be {@code null} for anonymous access).
     * @param secretKey  secret access key (may be {@code null}).
     * @param pathStyle  use path-style addressing ({@code endpoint/bucket/key})
     *                   instead of virtual-host style; needed by MinIO and most
     *                   S3-compatible services.
     */
    record Settings(String endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
    }

    /** Open a store for {@code settings}. Caller closes the returned store. */
    ObjectStore create(Settings settings) throws IOException;
}
