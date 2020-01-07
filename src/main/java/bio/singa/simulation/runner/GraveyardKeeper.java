package bio.singa.simulation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * @author cl
 */
public class GraveyardKeeper {

    private static final Logger logger = LoggerFactory.getLogger(GraveyardKeeper.class);

    private static SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss-SSS'Z'");
    private static long deathThreshold = 5 * 60 * 1000;

    public static void main(String[] args) throws IOException {

        Path rootDirectory = Paths.get("/mnt/sim/out");
        try (DirectoryStream<Path> setupPaths = Files.newDirectoryStream(rootDirectory)) {
            for (Path setupPath : setupPaths) {
                if (Files.isDirectory(setupPath)) {
                    checkForDeadSimulations(setupPath);
                }
            }
        }

    }

    private static void checkForDeadSimulations(Path rootDirectory) {
        // traverse all observation directories
        if (!Files.exists(rootDirectory)) {
            return;
        }
        List<String> deathList = new ArrayList<>();
        try (DirectoryStream<Path> observationDirectoryStream = Files.newDirectoryStream(rootDirectory)) {
            for (Path observationDirectoryPath : observationDirectoryStream) {
                if (Files.isDirectory(observationDirectoryPath)) {
                    String timeStamp = observationDirectoryPath.getFileName().toString();
                    try {
                        simpleDateFormat.parse(timeStamp);
                    } catch (ParseException e) {
                        continue;
                    }

                    // get alive file
                    Path aliveFile = observationDirectoryPath.resolve("alive");
                    if (!Files.exists(aliveFile)) {
                        deathList.add(timeStamp);
                        continue;
                    }
                    String aliveTime = String.join("", Files.readAllLines(aliveFile));
                    if (!aliveTime.equals("done")) {
                        long lastLifeSign = Long.parseLong(aliveTime);
                        long currentTime = System.currentTimeMillis();
                        // 5 minutes
                        if (currentTime - lastLifeSign > deathThreshold) {
                            deathList.add(timeStamp);
                        }
                    }
                }
            }
        } catch (IOException e) {
            logger.warn("Unable to retrieve simulation paths from " + rootDirectory + ".", e);
        }
        if (deathList.isEmpty()) {
            System.out.println("No simulations found to clear in "+ rootDirectory);
        }
        for (String deadSimulation : deathList) {
            System.out.println("Simulation " + rootDirectory.resolve(deadSimulation) + " seems to be dead, removing it. ");
            Path deadFolder = rootDirectory.resolve(deadSimulation);
            deadFolder.resolve("variations.json").toFile().delete();
            deadFolder.resolve("alive").toFile().delete();
            deadFolder.toFile().delete();
        }
    }


}
