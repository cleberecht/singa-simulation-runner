package bio.singa.simulation.runner;

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
public class ProgressBarHandler  {

    private SimulationStatus status;
    private ProgressBar progressBar;

    public ProgressBarHandler(SimulationStatus status) {
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
        //progressBar.addBitOfInformation(new BitOfInformation("etf", status::getEstimatedFinish));
        //progressBar.addBitOfInformation(new BitOfInformation("local error", status::getLocalErrorStatus));

    }

    public void tearDown() {
        progressBar.close();
    }

}
