package software.amazon.awssdk.services.s3;

/** Test stub of the real S3 client. */
public interface S3Client {

    byte[] getObject(String bucket, String key);
}
