package bio.singa.simulation.runner.cli;

import me.tongfei.progressbar.BitOfInformation;
import me.tongfei.progressbar.ProgressBar;
import me.tongfei.progressbar.ProgressBarBuilder;
import me.tongfei.progressbar.ProgressBarStyle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static picocli.CommandLine.Parameters;

/**
 * @author cl
 */
@CommandLine.Command(description = "Monitor and clean simulation process",
        name = "simulation-overseer",
        version = "v0.0.2",
        mixinStandardHelpOptions = true)
public class SimulationOverseer implements Callable<Void> {

    private static final Logger logger = LoggerFactory.getLogger(SimulationOverseer.class);

    @Parameters(index = "0",
            description = "The folder, where ticket are pulled from.")
    private Path ticketDirectory = Paths.get("tickets");

    @Parameters(index = "1",
            description = "The folder, where simulation results are written")
    private Path targetDirectory = Paths.get("");

    private ProgressBar progressBar;

    private Path openTicketPath;
    private Path processingPath;
    private Path donePath;

    public static void main(String[] args) {
        CommandLine.call(new SimulationOverseer(), args);
    }

    @Override
    public Void call() throws Exception {

        openTicketPath = ticketDirectory.resolve("open");
        processingPath = ticketDirectory.resolve("processing");
        donePath = ticketDirectory.resolve("done");

        progressBar = new ProgressBarBuilder()
                .setInitialMax(countFiles(openTicketPath) + countFiles(processingPath) + countFiles(donePath))
                .setUpdateIntervalMillis(10000)
                .setStyle(ProgressBarStyle.COLORFUL_UNICODE_BLOCK)
                .setTaskName("progress")
                .build();

        progressBar.bind(this::getNumberOfClosedTickets);
        progressBar.addBitOfInformation(new BitOfInformation("currently processing", this::currentlyProcessingTickets));

        ScheduledExecutorService ses = Executors.newScheduledThreadPool(1);

        Callable<Void> callableTask = () -> {
            checkForDeadSimulations();
            return null;
        };

        ses.schedule(callableTask, 5, TimeUnit.MINUTES);

        return null;
    }

    private String currentlyProcessingTickets() {
        return String.valueOf(countFiles(processingPath));
    }

    private long getNumberOfClosedTickets() {
        return countFiles(donePath);
    }

    private void checkForDeadSimulations() {
        // traverse all observation directories
        if (!Files.exists(targetDirectory)) {
            return;
        }
        List<String> deathList = new ArrayList<>();
        // cross check processing tickets
        try (DirectoryStream<Path> processingDirectoryStream = Files.newDirectoryStream(processingPath)) {
            for (Path processingDirectoryPath : processingDirectoryStream) {
                if (!Files.isDirectory(processingDirectoryPath)) {
                    String ticketIdentifier = processingDirectoryPath.getFileName().toString();
                    // get alive file
                    Path aliveFile = targetDirectory.resolve(ticketIdentifier).resolve("alive");
                    if (simulationFailed(aliveFile)) {
                        deathList.add(ticketIdentifier);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to retrieve processing tickets from " + processingPath + ".", e);
        }

        // check observations
        try (DirectoryStream<Path> observationDirectoryStream = Files.newDirectoryStream(targetDirectory)) {
            for (Path observationDirectoryPath : observationDirectoryStream) {
                if (Files.isDirectory(observationDirectoryPath)) {
                    String ticketIdentifier = observationDirectoryPath.getFileName().toString();
                    // get alive file
                    Path aliveFile = observationDirectoryPath.resolve("alive");
                    if (simulationFailed(aliveFile)) {
                        deathList.add(ticketIdentifier);
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to retrieve simulation paths from " + targetDirectory + ".", e);
        }

        for (String ticketIdentifier : deathList) {
            System.out.println("Simulation " + ticketIdentifier + " seems to be dead, removing it. ");
            Path deadFolder = targetDirectory.resolve(ticketIdentifier);
            // reopen ticket
            try {
                Path processing = processingPath.resolve(ticketIdentifier);
                if (Files.exists(processing)) {
                    Files.move(processing, openTicketPath.resolve(ticketIdentifier));
                }
                Path done = donePath.resolve(ticketIdentifier);
                if (Files.exists(done)) {
                    Files.move(done, openTicketPath.resolve(ticketIdentifier));
                }
            } catch (IOException e) {
                logger.warn("Unable to reopen ticket " + ticketIdentifier + ".");
                continue;
            }
            // remove dead simulation
            deadFolder.resolve("variations.json").toFile().delete();
            deadFolder.resolve("alive").toFile().delete();
            deadFolder.toFile().delete();
        }
    }

    private boolean simulationFailed(Path aliveFile) {
        // no file exists
        if (!Files.exists(aliveFile)) {
            return true;
        }
        // read file
        String aliveTime;
        try {
            aliveTime = String.join("", Files.readAllLines(aliveFile));
        } catch (IOException e) {
            // unale to read file
            logger.warn("unable to read alive file {}", aliveFile);
            return true;
        }
        // simulation is done
        if (aliveTime.equals("done")) {
            return false;
        }
        // check timestamp
        long lastLifeSign = Long.parseLong(aliveTime);
        long currentTime = System.currentTimeMillis();
        // 5 minutes no sign
        long deathThreshold = 5 * 60 * 1000;
        return currentTime - lastLifeSign > deathThreshold;
    }

    private long countFiles(Path directoryPath) {
        try (Stream<Path> files = Files.list(directoryPath)) {
            return files.count();
        } catch (IOException e) {
            logger.warn("unable to count files in {}", directoryPath);
            return 0;
        }
    }


}
