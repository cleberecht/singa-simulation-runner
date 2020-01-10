package bio.singa.simulation.runner.converters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;
import systems.uom.ucum.format.UCUMFormat;
import tech.units.indriya.quantity.QuantityDimension;

import javax.measure.Unit;
import javax.measure.format.UnitFormat;
import javax.measure.quantity.Time;

import static tech.units.indriya.unit.MetricPrefix.MICRO;
import static tech.units.indriya.unit.Units.SECOND;

/**
 * @author cl
 */
public class TimeUnitConverter implements CommandLine.ITypeConverter<Unit<Time>> {

    private static final Logger logger = LoggerFactory.getLogger(TimeUnitConverter.class);

    private static UnitFormat unitFormatService = UCUMFormat.getInstance(UCUMFormat.Variant.CASE_SENSITIVE);

    @Override
    public Unit<Time> convert(String unitString) {
        Unit<?> unit = unitFormatService.parse(unitString);
        System.out.println(unitFormatService.format(MICRO(SECOND)));
        if (unit.getDimension().equals(QuantityDimension.TIME)) {
            return unit.asType(Time.class);
        }
        logger.error("unable to convert time unit {}", unitString);
        throw new IllegalArgumentException(unitString);
    }
}
