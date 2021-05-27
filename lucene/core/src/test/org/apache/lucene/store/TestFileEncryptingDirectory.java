package org.apache.lucene.store;

import org.junit.Ignore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

public class TestFileEncryptingDirectory extends BaseDirectoryTestCase {

    @Override
    protected Directory getDirectory(Path path) throws IOException {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 31);

        return new FileEncryptingDirectory(new NIOFSDirectory(path), fileName -> key);
    }


    @Override
    @Ignore("file size will never match since we have overhead due to the encryption")
    public void testCopyBytes() throws Exception {
        super.testCopyBytes();
    }

    @Override
    public void testThreadSafetyInListAll() throws Exception {
        for (int i = 0; i < 100; i++) {
            super.testThreadSafetyInListAll();
        }
    }

}
