package com.routemesh.shipmentservice.storage;

import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.routemesh.shipmentservice.service.ShipmentException;
import com.routemesh.shipmentservice.service.ShipmentException.Reason;

import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

public class S3PodStorage implements PodStorage {

    private static final Logger log = LoggerFactory.getLogger(S3PodStorage.class);

    private final S3Client s3;
    private final String bucket;

    public S3PodStorage(S3Client s3, String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        try {
            s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType)
                    .contentLength((long) content.length).build(), RequestBody.fromBytes(content));
        } catch (SdkException e) {
            log.error("Could not store object {}", key, e);
            throw new ShipmentException(Reason.STORAGE_UNAVAILABLE, "proof of delivery storage is unavailable");
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return s3.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build());
        } catch (SdkException e) {
            log.error("Could not read object {}", key, e);
            throw new ShipmentException(Reason.STORAGE_UNAVAILABLE, "proof of delivery storage is unavailable");
        }
    }
}
