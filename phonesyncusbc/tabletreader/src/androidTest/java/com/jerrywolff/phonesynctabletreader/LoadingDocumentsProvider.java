package com.jerrywolff.phonesynctabletreader;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public final class LoadingDocumentsProvider extends ContentProvider {
    public static final String AUTHORITY = "com.jerrywolff.phonesynctabletreader.cloudtest";
    public static final String ROOT_ID = "cloud-root";

    private static final String FIRST_FILE_ID = "first-file";
    private static final String LATE_FILE_ID = "late-file";
    private static final byte[] FIRST_CONTENT = "already available".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LATE_CONTENT = "arrived while OneDrive was loading".getBytes(StandardCharsets.UTF_8);
    private static final AtomicInteger CHILD_QUERY_COUNT = new AtomicInteger();
    private static final String[] DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        String documentId = DocumentsContract.getDocumentId(uri);
        if (uri.getPathSegments().contains("children")) {
            return queryChildren(documentId, projection);
        }
        return queryDocument(documentId, projection);
    }

    private Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        if (ROOT_ID.equals(documentId)) {
            CHILD_QUERY_COUNT.set(0);
            addDocument(cursor, ROOT_ID, "Cloud test", DocumentsContract.Document.MIME_TYPE_DIR, 0);
        } else if (FIRST_FILE_ID.equals(documentId)) {
            addDocument(cursor, FIRST_FILE_ID, "first.txt", "text/plain", FIRST_CONTENT.length);
        } else if (LATE_FILE_ID.equals(documentId)) {
            addDocument(cursor, LATE_FILE_ID, "uploaded-later.txt", "text/plain", LATE_CONTENT.length);
        }
        return cursor;
    }

    private Cursor queryChildren(String parentDocumentId, String[] projection) {
        int queryNumber = CHILD_QUERY_COUNT.incrementAndGet();
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_PROJECTION);
        if (ROOT_ID.equals(parentDocumentId)) {
            addDocument(cursor, FIRST_FILE_ID, "first.txt", "text/plain", FIRST_CONTENT.length);
            if (queryNumber > 1) {
                addDocument(cursor, LATE_FILE_ID, "uploaded-later.txt", "text/plain", LATE_CONTENT.length);
            }
        }
        Bundle extras = new Bundle();
        extras.putBoolean(DocumentsContract.EXTRA_LOADING, queryNumber == 1);
        cursor.setExtras(extras);
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String documentId = DocumentsContract.getDocumentId(uri);
        byte[] content;
        if (FIRST_FILE_ID.equals(documentId)) {
            content = FIRST_CONTENT;
        } else if (LATE_FILE_ID.equals(documentId)) {
            content = LATE_CONTENT;
        } else {
            throw new FileNotFoundException("Unknown cloud test document: " + documentId);
        }
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            Thread writer = new Thread(() -> {
                try (ParcelFileDescriptor.AutoCloseOutputStream output =
                             new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                    output.write(content);
                } catch (IOException ignored) {
                }
            }, "cloud-test-document-writer");
            writer.start();
            return pipe[0];
        } catch (IOException exception) {
            throw new FileNotFoundException(exception.getMessage());
        }
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException();
    }

    private static void addDocument(MatrixCursor cursor, String id, String name, String mimeType, int size) {
        Map<String, Object> values = new HashMap<>();
        values.put(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id);
        values.put(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name);
        values.put(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeType);
        values.put(DocumentsContract.Document.COLUMN_SIZE, size);
        values.put(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L);
        values.put(DocumentsContract.Document.COLUMN_FLAGS, 0);
        addValues(cursor, values);
    }

    private static void addValues(MatrixCursor cursor, Map<String, Object> values) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) {
            row.add(column, values.get(column));
        }
    }
}