package bio.singa.simulation.runner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import systems.uom.ucum.format.UCUMFormat;
import tec.units.indriya.quantity.Quantities;
import tec.units.indriya.quantity.QuantityDimension;

import javax.measure.Quantity;
import javax.measure.Unit;
import javax.measure.format.UnitFormat;
import javax.measure.quantity.Time;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author cl
 */
public class TimeQuantityConverter implements CommandLine.ITypeConverter<Quantity<Time>> {

    private static final Logger logger = LoggerFactory.getLogger(TimeQuantityConverter.class);

    private static Pattern timePattern = Pattern.compile("([0-9]*\\.?[0-9]+([eE][-+]?[0-9]+)?)(\\w+)");
    private static UnitFormat unitFormatService = UCUMFormat.getInstance(UCUMFormat.Variant.CASE_SENSITIVE);

    @Override
    public Quantity<Time> convert(String timeString) {
        Matcher matcher = timePattern.matcher(timeString);
        if (matcher.matches()) {
            double quantity = Double.valueOf(matcher.group(1));
            String unitString = matcher.group(3);
            Unit<?> unit = unitFormatService.parse(unitString);
            if (unit.getDimension().equals(QuantityDimension.TIME)) {
                return Quantities.getQuantity(quantity, unit).asType(Time.class);
            }
        }
        logger.error("unable to convert time {}", timeString);
        throw new IllegalArgumentException(timeString);
    }

}
