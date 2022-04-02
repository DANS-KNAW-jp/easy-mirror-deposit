/*
 * Copyright (C) 2022 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.easy.mirror.core;

import io.dropwizard.lifecycle.Managed;
import org.apache.commons.io.monitor.FileAlterationListenerAdaptor;
import org.apache.commons.io.monitor.FileAlterationMonitor;
import org.apache.commons.io.monitor.FileAlterationObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;

public class MirroringService implements Managed {
    private static final Logger log = LoggerFactory.getLogger(MirroringService.class);
    private final ExecutorService executorService;
    private final int pollingInterval;
    private final Path inbox;
    private final Path outbox;
    private final Path workDirectory;
    private final Path mirrorStore;

    private boolean initialized = false;
    private boolean tasksCreatedInitialization = false;

    private class EventHandler extends FileAlterationListenerAdaptor {
        @Override
        public void onStart(FileAlterationObserver observer) {
            log.trace("onStart called");
            if (!initialized) {
                initialized = true;
                processAllFromInbox();
            }
        }

        @Override
        public void onFileCreate(File file) {
            log.trace("onFileCreate: {}", file);
            if (tasksCreatedInitialization) {
                tasksCreatedInitialization = false;
                return; // file already added to queue by onStart
            }
            processDatasetVersionExport(file.toPath());
        }
    }

    public MirroringService(ExecutorService executorService, int pollingInterval, Path inbox, Path outbox, Path workDirectory, Path mirrorStore) {
        this.executorService = executorService;
        this.pollingInterval = pollingInterval;
        this.inbox = inbox;
        this.outbox = outbox;
        this.workDirectory = workDirectory;
        this.mirrorStore = mirrorStore;
    }

    @Override
    public void start() throws Exception {
        FileAlterationObserver observer = new FileAlterationObserver(inbox.toFile(), f -> f.isFile() && f.getParentFile().equals(inbox.toFile()));
        observer.addListener(new EventHandler());
        FileAlterationMonitor monitor = new FileAlterationMonitor(pollingInterval);
        monitor.addObserver(observer);
        try {
            monitor.start();
        }
        catch (Exception e) {
            throw new IllegalStateException(String.format("Could not start monitoring %s", inbox), e);
        }
    }

    private void processAllFromInbox() {
        try {
            Files.list(inbox)
                .forEach(dve -> {
                    processDatasetVersionExport(dve);
                    tasksCreatedInitialization = true;
                });
        }
        catch (IOException e) {
            throw new IllegalStateException("Could not read DVEs from inbox", e);
        }
    }

    private void processDatasetVersionExport(Path dve) {
        try {
            Files.move(dve, workDirectory);
            executorService.execute(new MirrorTask(dve, outbox, mirrorStore));
        }
        catch (IOException e) {
            log.error("Could not move DVE to work diretory", e);
        }
    }

    public void stop() {
        executorService.shutdown();
    }
}
