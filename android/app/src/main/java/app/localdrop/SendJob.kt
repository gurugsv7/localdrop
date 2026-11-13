package app.localdrop
import android.app.job.JobService
import android.app.job.JobParameters
import java.util.concurrent.Executors
class SendJob: JobService() {
    private var worker: java.util.concurrent.ExecutorService? = null
    override fun onStartJob(params: JobParameters): Boolean {
        worker=Executors.newSingleThreadExecutor().also { executor -> executor.execute {
            val failed=runCatching { Queue.sendAll(this).isNotEmpty() }.getOrDefault(true)
            jobFinished(params,failed); executor.shutdown()
        } }; return true
    }
    override fun onStopJob(params: JobParameters): Boolean { Native.cancel();worker?.shutdownNow(); return true }
    override fun onDestroy() { worker?.shutdownNow(); super.onDestroy() }
}
