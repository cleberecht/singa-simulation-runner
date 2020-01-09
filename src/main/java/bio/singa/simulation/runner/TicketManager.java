package bio.singa.simulation.runner;

import bio.singa.exchange.ProcessingTicket;
import bio.singa.exchange.features.FeatureRepresentation;
import bio.singa.features.model.Feature;
import bio.singa.features.model.FeatureRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * @author cl
 */
public class TicketManager {

    private static final Logger logger = LoggerFactory.getLogger(TicketManager.class);

    private static Pattern uuidPattern = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private Path ticketPath;

    private Path openTicketPath;
    private Path processingPath;
    private Path donePath;


    public TicketManager(Path ticketPath) {
        this.ticketPath = ticketPath;
        openTicketPath = ticketPath.resolve("open");
        processingPath = ticketPath.resolve("processing");
        donePath = ticketPath.resolve("done");
    }

    public synchronized Optional<ProcessingTicket> pullTicket() {
        try (DirectoryStream<Path> ticketFileStream = Files.newDirectoryStream(openTicketPath)) {
            Iterator<Path> iterator = ticketFileStream.iterator();
            if (iterator.hasNext()) {
                boolean validTicket = false;
                ProcessingTicket ticket = null;
                String ticketId;
                do {
                    Path currentTicket = iterator.next();
                    ticketId = currentTicket.getFileName().toString();
                    if (!isUUID(ticketId)) {
                        continue;
                    }
                    try {
                        String json = String.join("\n", Files.readAllLines(currentTicket));
                        ticket = ProcessingTicket.fromJson(json);
                        // copy to processing folder
                        Files.copy(currentTicket, processingPath.resolve(ticketId));
                        validTicket = true;
                    } catch (IOException e) {
                        logger.warn("currently unable to process " + ticketId);
                        validTicket = false;
                    }
                } while (!validTicket);
                // remove ticket
                if (ticket != null) {
                    Files.deleteIfExists(openTicketPath.resolve(ticketId));
                    return Optional.of(ticket);
                }
            }
        } catch (IOException e) {
            logger.warn("unable to retrieve any ticket", e);
        }
        return Optional.empty();
    }

    public void redeemTicket(ProcessingTicket ticketData) {
        for (FeatureRepresentation<?> featureRepresentation : ticketData.getFeatures()) {
            Feature<?> feature = FeatureRegistry.get(featureRepresentation.getIdentifier());
            Object content = featureRepresentation.fetchContent();
            List<?> alternativeValues = featureRepresentation.getAlternativeValues();
            int alternativeContentIndex = alternativeValues.indexOf(content);
            feature.setAlternativeContent(alternativeContentIndex);
        }
    }

    public boolean ticketsAvailable() {
        try (DirectoryStream<Path> ticketFileStream = Files.newDirectoryStream(openTicketPath)) {
            for (Path ticketFile : ticketFileStream) {
                if (isAvailable(ticketFile)) {
                    return true;
                }
            }
        } catch (IOException e) {
            logger.warn("unable to retrieve any ticket", e);
        }
        return false;
    }

    public void closeTicket(ProcessingTicket ticketData) {
        try {
            Files.move(processingPath.resolve(ticketData.getIdentifier()), donePath.resolve(ticketData.getIdentifier()));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private boolean lockAcquired(FileLock fileLock) {
        return fileLock != null;
    }

    private boolean isUUID(String uuid) {
        return uuidPattern.matcher(uuid).matches();
    }

    private boolean isAvailable(Path filePath) {
        File file = filePath.toFile();
        return file.isFile() && file.exists() && file.length() > 0;
    }

}
