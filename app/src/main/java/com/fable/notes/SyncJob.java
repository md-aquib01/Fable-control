package com.fable.notes;

import android.app.job.JobParameters;
import android.app.job.JobService;

public class SyncJob extends JobService {
    @Override
    public boolean onStartJob(JobParameters p) {
        new Thread(() -> {
            Sync.run(getApplicationContext());
            jobFinished(p, false);
        }, "fable-sync-job").start();
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters p) { return true; }
}
