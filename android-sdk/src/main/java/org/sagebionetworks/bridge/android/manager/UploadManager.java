package org.sagebionetworks.bridge.android.manager;

import com.google.common.io.BaseEncoding;
import com.google.common.io.ByteSink;
import com.google.common.io.FileWriteMode;
import com.google.common.io.Files;

import org.sagebionetworks.bridge.android.data.Archive;
import org.sagebionetworks.bridge.android.data.StudyUploadEncryptor;
import org.sagebionetworks.bridge.android.manager.upload.S3Service;
import org.sagebionetworks.bridge.android.util.retrofit.RxUtils;
import org.sagebionetworks.bridge.rest.api.ForConsentedUsersApi;
import org.sagebionetworks.bridge.rest.model.UploadRequest;
import org.sagebionetworks.bridge.rest.model.UploadSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongycastle.cms.CMSException;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import rx.Completable;
import rx.Single;

/**
 * Created by jyliu on 1/30/2017.
 */

public class UploadManager {
    private static final Logger LOG = LoggerFactory.getLogger(UploadManager.class);
    private static final String CONTENT_TYPE_DATA_ARCHIVE = "application/zip";

    private final ForConsentedUsersApi api;
    private final StudyUploadEncryptor encryptor;
    private final S3Service s3Service;

    public UploadManager(AuthenticationManager authenticationManager, StudyUploadEncryptor
            encryptor) {
        this.api = authenticationManager.getApi();

        this.encryptor = encryptor;

        this.s3Service = null;
    }

    public static class UploadFile {
        public String filename;
        public String contentType;
        public long fileLength;
        public String md5Hash;
    }

    public Completable upload(String filename, Archive archive) throws
            IOException, CMSException {

        Single<UploadFile> uploadFileSingle = Single
                .fromCallable(() -> persist(filename, archive));

        Single<UploadSession> uploadSessionSingle = uploadFileSingle
                .flatMap(this::requestUploadSession);

        return Single.zip(uploadFileSingle, uploadSessionSingle, this::upload)
                .flatMap(i -> i)
                .toCompletable();
    }

    public Single<UploadSession> upload(UploadFile uploadFile, UploadSession session) {

        File file = new File(uploadFile.filename);
        RequestBody requestBody = RequestBody.create(MediaType.parse(uploadFile.contentType), file);

        return RxUtils.toBodySingle(
                s3Service.uploadToS3(
                        session.getUrl(),
                        requestBody,
                        uploadFile.md5Hash,
                        uploadFile.contentType))
                .toCompletable()
                .doOnCompleted(() -> {
                    // TODO: update upload status in DB, delete file
                    LOG.info("Upload succeeded for id: " + session.getId());
                }).andThen(
                        RxUtils.toBodySingle(api.completeUploadSession(session.getId())
                        ).onErrorReturn((t) -> {
                            LOG.info("Failed to call upload complete, server will recover", t);
                            return session;
                        }));
    }

    public Single<UploadSession> requestUploadSession(UploadFile uploadFile) {
        return RxUtils.toBodySingle(
                api.requestUploadSession(
                        new UploadRequest()
                                .name(uploadFile.filename)
                                .contentType(uploadFile.contentType)
                                .contentLength(uploadFile.fileLength)
                                .contentMd5(uploadFile.md5Hash)))
                .doOnSuccess((uploadSession) -> {
                    // TODO: insert upload session to DB, link to file metadata
                    LOG.info("Received upload session with id: " + uploadSession.getId());
                });
    }

    public UploadFile persist(String filename, Archive archive) throws IOException,
            CMSException, NoSuchAlgorithmException {
        File file = new File(filename);

        ByteSink sink = Files.asByteSink(file, FileWriteMode.APPEND);

        MessageDigest md5 = null;
        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            LOG.error("MD5 digest not found", e);
            throw e;
        }

        DigestOutputStream md5OutStream = new DigestOutputStream(sink.openBufferedStream(), md5);

        OutputStream encryptedOutputStream = encryptor.encrypt(md5OutStream);

        archive.writeTo(encryptedOutputStream);

        String md5Hash = BaseEncoding.base64().encode(md5.digest());

        //TODO: write file metadata to db

        UploadFile uploadFile = new UploadFile();
        uploadFile.filename = filename;
        uploadFile.contentType = CONTENT_TYPE_DATA_ARCHIVE;
        uploadFile.fileLength = file.length();
        uploadFile.md5Hash = md5Hash;

        return uploadFile;
    }
}
