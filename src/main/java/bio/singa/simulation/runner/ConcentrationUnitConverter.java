package bio.singa.simulation.runner;

import bio.singa.features.quantities.MolarConcentration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import javax.measure.Unit;
import javax.measure.format.UnitFormat;
import javax.measure.spi.ServiceProvider;

import static bio.singa.features.units.UnitProvider.MOLE_PER_LITRE;

/**
 * @author cl
 */
public class ConcentrationUnitConverter implements CommandLine.ITypeConverter<Unit<MolarConcentration>> {

    private static final Logger logger = LoggerFactory.getLogger(ConcentrationUnitConverter.class);

    @Override
    public Unit<MolarConcentration> convert(String unitString) {
        UnitFormat unitFormatService = ServiceProvider.current().getUnitFormatService().getUnitFormat("ASCII");
        Unit<?> unit = unitFormatService.parse(unitString);
        if (unit.isCompatible(MOLE_PER_LITRE)) {
            return unit.asType(MolarConcentration.class);
        }
        logger.error("unable to convert time {}", unitString);
        throw new IllegalArgumentException(unitString);
    }
}
