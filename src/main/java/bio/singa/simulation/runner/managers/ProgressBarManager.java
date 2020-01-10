package bio.singa.simulation.runner.managers;

import bio.singa.simulation.model.simulation.SimulationStatus;
import me.tongfei.progressbar.BitOfInformation;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;

import static tech.units.indriya.unit.MetricPrefix.MILLI;
import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
public class ProgressBarManager {

    private SimulationStatus status;
    private ProgressBar progressBar;

    public ProgressBarManager(SimulationStatus status) {
        this.status = status;
        progressBar = new ProgressBarBuilder()
                .setInitialMax(status.getTerminationTime().to(MILLI(SECOND)).getValue().longValue())
                .setUpdateIntervalMillis(1000)
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setUnit("ms", 1)
                .setTaskName("progress")
                .build();
        progressBar.bind(status::getProgressInMilliSeconds);
        progressBar.addBitOfInformation(new BitOfInformation("simulation time passed", status::getElapsedTime));
        progressBar.addBitOfInformation(new BitOfInformation("speed", status::getEstimatedSpeed));
    }

    public void tearDown() {
        progressBar.close();
    }

}
