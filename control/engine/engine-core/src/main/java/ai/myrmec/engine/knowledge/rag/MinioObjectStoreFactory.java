package ai.myrmec.engine.knowledge.rag;

import io.minio.GetObjectArgs;
import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.Result;
import io.minio.messages.Item;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link ObjectStoreFactory} backed by the MinIO Java client, which
 * speaks the S3 protocol and therefore works against AWS S3, Cloudflare R2,
 * Backblaze B2, DigitalOcean Spaces, MinIO, and GCS's S3-interop endpoint from
 * a single small dependency (no per-provider SDK).
 */
@Component
class MinioObjectStoreFactory implements ObjectStoreFactory {

    @Override
    public ObjectStore create(Settings settings) {
        MinioClient.Builder builder = MinioClient.builder().endpoint(settings.endpoint());
        if (settings.accessKey() != null && !settings.accessKey().isBlank()) {
            builder.credentials(settings.accessKey(), settings.secretKey());
        }
        if (settings.region() != null && !settings.region().isBlank()) {
            builder.region(settings.region());
        }
        return new MinioObjectStore(builder.build());
    }

    /** {@link ObjectStore} over a {@link MinioClient}; maps SDK errors to IOException. */
    private record MinioObjectStore(MinioClient client) implements ObjectStore {

        @Override
        public List<Entry> list(String bucket, String prefix) throws IOException {
            List<Entry> entries = new ArrayList<>();
            ListObjectsArgs args = ListObjectsArgs.builder()
                    .bucket(bucket)
                    .prefix(prefix == null ? "" : prefix)
                    .recursive(true)
                    .build();
            try {
                for (Result<Item> result : client.listObjects(args)) {
                    Item item = result.get();
                    if (item.isDir()) {
                        continue;
                    }
                    entries.add(new Entry(item.objectName(), item.size()));
                }
            } catch (Exception e) {
                throw new IOException("listObjects failed for bucket '" + bucket + "': " + e.getMessage(), e);
            }
            return entries;
        }

        @Override
        public byte[] read(String bucket, String key) throws IOException {
            GetObjectArgs args = GetObjectArgs.builder().bucket(bucket).object(key).build();
            try (InputStream stream = client.getObject(args)) {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("getObject failed for '" + bucket + "/" + key + "': " + e.getMessage(), e);
            }
        }

        @Override
        public void close() throws IOException {
            try {
                client.close();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Failed to close MinIO client: " + e.getMessage(), e);
            }
        }
    }
}
