package org.noise_planet.noisemodelling.jdbc;

import org.h2gis.utilities.JDBCUtilities;
import org.h2gis.utilities.SpatialResultSet;
import org.h2gis.utilities.TableLocation;
import org.h2gis.utilities.Tuple;
import org.h2gis.utilities.dbtypes.DBUtils;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.operation.linemerge.LineMerger;
import org.noise_planet.noisemodelling.emission.EvaluateRailwaySourceCnossos;
import org.noise_planet.noisemodelling.emission.RailWayLW;
import org.noise_planet.noisemodelling.emission.RailwayTrackParametersCnossos;
import org.noise_planet.noisemodelling.emission.RailwayVehicleParametersCnossos;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.*;

import static org.noise_planet.noisemodelling.jdbc.MakeParallelLines.MakeParallelLine;



public class RailWayLWIterator implements Iterator<RailWayLWIterator.RailWayLWGeom> {
    private final EvaluateRailwaySourceCnossos evaluateRailwaySourceCnossos = new EvaluateRailwaySourceCnossos();
    private Connection connection;
    private RailWayLWGeom railWayLWComplete = null;
    private RailWayLWGeom railWayLWIncomplete = new RailWayLWGeom();
    private String tableTrackGeometry;
    private String tableTrainTraffic;
    private SpatialResultSet spatialResultSet;
    public Map<String, Integer> sourceFields = null;

    /**
     * Generate sound source for train (with train source directivity) from traffic and geometry tracks tables
     * @param connection
     * @param tableTrackGeometry Track geometry and metadata
     * @param tableTrainTraffic Train traffic associated with tracks
     */
    public RailWayLWIterator(Connection connection, String tableTrackGeometry, String tableTrainTraffic) {
        this.connection = connection;
        this.tableTrackGeometry = tableTrackGeometry;
        this.tableTrainTraffic = tableTrainTraffic;
        railWayLWComplete = fetchNext(railWayLWIncomplete);
    }

    @Override
    public boolean hasNext() {
        return railWayLWComplete != null;
    }

    private List<LineString> splitGeometry(Geometry geometry){
        List<LineString> inputLineStrings = new ArrayList<>();
        for (int id = 0; id < geometry.getNumGeometries(); id++) {
            Geometry subGeom = geometry.getGeometryN(id);
            if (subGeom instanceof LineString) {
                inputLineStrings.add((LineString) subGeom);
            }
        }
        return inputLineStrings;
    }

    public static boolean hasColumn(SpatialResultSet rs, String columnName) throws SQLException {
        ResultSetMetaData rsmd = rs.getMetaData();
        int columns = rsmd.getColumnCount();
        for (int x = 1; x <= columns; x++) {
            if (columnName.equals(rsmd.getColumnName(x))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public RailWayLWGeom next() {
        RailWayLWGeom current = railWayLWComplete;
        railWayLWComplete = fetchNext(railWayLWIncomplete);
        return current;
    }

    public RailWayLWGeom current() {
        return railWayLWComplete;
    }

    private RailWayLWGeom fetchNext(RailWayLWGeom incompleteRecord) {
        RailWayLWGeom completeRecord = null;
        try {
            boolean hasNext = false;
            if (spatialResultSet == null) {
                Tuple<String, Integer> trackKey = JDBCUtilities.getIntegerPrimaryKeyNameAndIndex(connection,
                        TableLocation.parse(tableTrackGeometry, DBUtils.getDBType(connection)));
                spatialResultSet = connection.createStatement().executeQuery(
                        "SELECT r1."+trackKey.first()+" trackid, r1.*, r2.* FROM " + tableTrackGeometry + " r1, " +
                                tableTrainTraffic + " r2 WHERE r1.IDSECTION=R2.IDSECTION ORDER BY R1." + trackKey.first())
                        .unwrap(SpatialResultSet.class);
                if(!spatialResultSet.next()) {
                    return null;
                }
                hasNext = true;
                if (sourceFields == null) {
                    sourceFields = new HashMap<>();
                    int fieldId = 1;
                    for (String fieldName : JDBCUtilities.getColumnNames(spatialResultSet.getMetaData())) {
                        sourceFields.put(fieldName.toUpperCase(), fieldId++);
                    }
                }
                if (sourceFields.containsKey("TRACKSPC")) {
                    incompleteRecord.distance = spatialResultSet.getDouble("TRACKSPC");
                }
                incompleteRecord.setRailWayLW(getRailwayEmissionFromResultSet(spatialResultSet, "DAY"));
                incompleteRecord.setRailWayLWDay(getRailwayEmissionFromResultSet(spatialResultSet, "DAY"));
                incompleteRecord.setRailWayLWEvening(getRailwayEmissionFromResultSet(spatialResultSet, "EVENING"));
                incompleteRecord.setRailWayLWNight(getRailwayEmissionFromResultSet(spatialResultSet, "NIGHT"));
                incompleteRecord.nbTrack = spatialResultSet.getInt("NTRACK");
                incompleteRecord.idSection = spatialResultSet.getString("IDSECTION");
                if (hasColumn(spatialResultSet, "GS")) {
                    incompleteRecord.gs = spatialResultSet.getDouble("GS");
                }
                incompleteRecord.pk = spatialResultSet.getInt("trackid");
                incompleteRecord.geometry = splitGeometry(spatialResultSet.getGeometry());
            }
            if(incompleteRecord.pk == -1) {
                return null;
            }
            while (spatialResultSet.next()) {
                hasNext = true;
                if (incompleteRecord.pk == spatialResultSet.getInt("trackid")) {
                    incompleteRecord.setRailWayLW(RailWayLW.sumRailWayLW(incompleteRecord.railWayLW, getRailwayEmissionFromResultSet(spatialResultSet, "DAY")));
                    incompleteRecord.setRailWayLWDay(RailWayLW.sumRailWayLW(incompleteRecord.railWayLWDay, getRailwayEmissionFromResultSet(spatialResultSet, "DAY")));
                    incompleteRecord.setRailWayLWEvening(RailWayLW.sumRailWayLW(incompleteRecord.railWayLWEvening, getRailwayEmissionFromResultSet(spatialResultSet, "EVENING")));
                    incompleteRecord.setRailWayLWNight(RailWayLW.sumRailWayLW(incompleteRecord.railWayLWNight, getRailwayEmissionFromResultSet(spatialResultSet, "NIGHT")));
                } else {
                    // railWayLWIncomplete is complete
                    completeRecord = new RailWayLWGeom(incompleteRecord);
                    // read next (incomplete) instance attributes for the next() call
                    incompleteRecord.geometry = splitGeometry(spatialResultSet.getGeometry());
                    if (sourceFields.containsKey("TRACKSPC")) {
                        incompleteRecord.distance = spatialResultSet.getDouble("TRACKSPC");
                    }
                    // initialize incomplete record
                    incompleteRecord.setRailWayLW(getRailwayEmissionFromResultSet(spatialResultSet, "DAY"));
                    incompleteRecord.setRailWayLWDay(getRailwayEmissionFromResultSet(spatialResultSet, "DAY"));
                    incompleteRecord.setRailWayLWEvening(getRailwayEmissionFromResultSet(spatialResultSet, "EVENING"));
                    incompleteRecord.setRailWayLWNight(getRailwayEmissionFromResultSet(spatialResultSet, "NIGHT"));
                    incompleteRecord.nbTrack = spatialResultSet.getInt("NTRACK");
                    incompleteRecord.idSection = spatialResultSet.getString("IDSECTION");
                    if (hasColumn(spatialResultSet, "GS")) {
                        incompleteRecord.gs = spatialResultSet.getDouble("GS");
                    }
                    incompleteRecord.pk = spatialResultSet.getInt("trackid");
                    incompleteRecord.geometry = splitGeometry(spatialResultSet.getGeometry());
                    break;
                }
            }
            if(!hasNext) {
                incompleteRecord.pk = -1;
            } else {
                if (completeRecord == null) {
                    completeRecord = new RailWayLWGeom(incompleteRecord);
                }
            }
            return completeRecord;
        } catch (SQLException | IOException throwables) {
            throw new NoSuchElementException(throwables.getMessage());
        }
    }

    /**
     * @param rs     result set of source
     * @param period D or E or N
     * @return Emission spectrum in dB
     */
    public RailWayLW getRailwayEmissionFromResultSet(ResultSet rs, String period) throws SQLException, IOException {
        String train = "FRET";
        double vehicleSpeed = 160;
        double vehiclePerHour = 1;
        int rollingCondition = 0;
        double idlingTime = 0;
        int trackTransfer = 4;
        int impactNoise = 0;
        int bridgeTransfert = 0;
        int curvature = 0;
        int railRoughness = 1;
        int nbTrack = 2;
        double vMaxInfra = 160;
        double commercialSpeed = 160;
        boolean isTunnel = false;
        String idSection = "";

        // Read fields
        if (sourceFields.containsKey("TRAINSPD")) {
            vehicleSpeed = rs.getDouble("TRAINSPD");
        }
        if (sourceFields.containsKey("T" + period)) {
            vehiclePerHour = rs.getDouble("T" + period);
        }
        if (sourceFields.containsKey("ROLLINGCONDITION")) {
            rollingCondition = rs.getInt("ROLLINGCONDITION");
        }
        if (sourceFields.containsKey("IDLINGTIME")) {
            idlingTime = rs.getDouble("IDLINGTIME");
        }
        if (sourceFields.containsKey("TRANSFER")) {
            trackTransfer = rs.getInt("TRANSFER");
        }
        if (sourceFields.containsKey("ROUGHNESS")) {
            railRoughness = rs.getInt("ROUGHNESS");
        }

        if (sourceFields.containsKey("IMPACT")) {
            impactNoise = rs.getInt("IMPACT");
        }
        if (sourceFields.containsKey("BRIDGE")) {
            bridgeTransfert = rs.getInt("BRIDGE");
        }
        if (sourceFields.containsKey("CURVATURE")) {
            curvature = rs.getInt("CURVATURE");
        }

        if (sourceFields.containsKey("TRACKSPD")) {
            vMaxInfra = rs.getDouble("TRACKSPD");
        }

        if (sourceFields.containsKey("COMSPD")) {
            commercialSpeed = rs.getDouble("COMSPD");
        }
        if (sourceFields.containsKey("TRAINTYPE")) {
            train = rs.getString("TRAINTYPE");
        }

        if (sourceFields.containsKey("TYPETRAIN")) {
            train = rs.getString("TYPETRAIN");
        }

        if (sourceFields.containsKey("ISTUNNEL")) {
            isTunnel = rs.getBoolean("ISTUNNEL");
        }

        if (sourceFields.containsKey("IDTUNNEL")) {
            String idTunnel = rs.getString("IDTUNNEL");
            isTunnel = idTunnel != null && !idTunnel.trim().isEmpty();
        }

        if (sourceFields.containsKey("NTRACK")) {
            nbTrack = rs.getInt("NTRACK");
        }


        RailWayLW  lWRailWay = new RailWayLW();

        RailwayTrackParametersCnossos trackParameters = new RailwayTrackParametersCnossos(vMaxInfra, trackTransfer, railRoughness,
                impactNoise, bridgeTransfert, curvature, commercialSpeed, isTunnel, nbTrack);

        Map<String, Integer> vehicles = evaluateRailwaySourceCnossos.getVehicleFromTrain(train);
       // double vehiclePerHouri=vehiclePerHour;
        if (vehicles!=null){
            int i = 0;
            for (Map.Entry<String,Integer> entry : vehicles.entrySet()){
                String typeTrain = entry.getKey();
                double vehiclePerHouri = vehiclePerHour * entry.getValue();
                if (vehiclePerHouri>0) {
                    RailwayVehicleParametersCnossos vehicleParameters = new RailwayVehicleParametersCnossos(typeTrain, vehicleSpeed,
                            vehiclePerHouri / (double) nbTrack, rollingCondition, idlingTime);

                    if (i == 0) {
                        lWRailWay = evaluateRailwaySourceCnossos.evaluate(vehicleParameters, trackParameters);
                    } else {
                        lWRailWay = RailWayLW.sumRailWayLW(lWRailWay, evaluateRailwaySourceCnossos.evaluate(vehicleParameters, trackParameters));
                    }
                }
                i++;
            }

        }else if (evaluateRailwaySourceCnossos.isInVehicleList(train)){
            if (vehiclePerHour>0) {
                RailwayVehicleParametersCnossos vehicleParameters = new RailwayVehicleParametersCnossos(train, vehicleSpeed,
                        vehiclePerHour / (double) nbTrack, rollingCondition, idlingTime);
                lWRailWay = evaluateRailwaySourceCnossos.evaluate(vehicleParameters, trackParameters);
            }
        }

        return lWRailWay;
    }


    public static class RailWayLWGeom {
        private RailWayLW railWayLW;
        private RailWayLW railWayLWDay;
        private RailWayLW railWayLWEvening;
        private RailWayLW railWayLWNight;
        private List<LineString> geometry;
        private int pk = -1;
        private int nbTrack;
        private String idSection;
        private double distance = 2;
        private double gs = 1.0;

        // Default constructor
        public RailWayLWGeom() {

        }

        public RailWayLWGeom(RailWayLWGeom other) {
            this.railWayLW = other.railWayLW;
            this.railWayLWDay = other.railWayLWDay;
            this.railWayLWEvening = other.railWayLWEvening;
            this.railWayLWNight = other.railWayLWNight;
            this.geometry = other.geometry;
            this.pk = other.pk;
            this.nbTrack = other.nbTrack;
            this.idSection = other.idSection;
            this.distance = other.distance;
            this.gs = other.gs;
        }

        public RailWayLWGeom(RailWayLW railWayLW, RailWayLW railWayLWDay, RailWayLW railWayLWEvening, RailWayLW railWayLWNight, List<LineString> geometry, int pk, int nbTrack, double distance, double gs) {
            this.railWayLW = railWayLW;
            this.railWayLWDay = railWayLWDay;
            this.railWayLWEvening = railWayLWEvening;
            this.railWayLWNight = railWayLWNight;
            this.geometry = geometry;
            this.pk = pk;
            this.nbTrack = nbTrack;
            this.distance = distance;
            this.gs = gs;
        }

        public double getGs() {
            return gs;
        }

        public void setGs(double gs) {
            this.gs = gs;
        }

        public double getDistance() {
            return distance;
        }

        public void setDistance(double distance) {
            this.distance = distance;
        }

        public RailWayLW getRailWayLW() {
            return railWayLW;
        }

        public void setRailWayLW(RailWayLW railWayLW) {
            this.railWayLW = railWayLW;
        }
        public RailWayLW getRailWayLWDay() {
            return railWayLWDay;
        }

        public void setRailWayLWDay(RailWayLW railWayLWDay) {
            this.railWayLWDay = railWayLWDay;
        }
        public RailWayLW getRailWayLWEvening() {
            return railWayLWEvening;
        }

        public void setRailWayLWEvening(RailWayLW railWayLWEvening) {
            this.railWayLWEvening = railWayLWEvening;
        }
        public RailWayLW getRailWayLWNight() {
            return railWayLWNight;
        }

        public void setRailWayLWNight(RailWayLW railWayLWNight) {
            this.railWayLWNight = railWayLWNight;
        }

        public int getNbTrack() {
            return nbTrack;
        }

        public String getIdSection() {
            return idSection;
        }

        public void setIdSection(String idSection) {
            this.idSection = idSection;
        }
        public void setNbTrack(int nbTrack) {
            this.nbTrack = nbTrack;
        }

        public List<LineString> getGeometry() {
            return  geometry;
        }


        public int getPK() {
            return pk;
        }

        public int setPK(int pk) {
            return this.pk=pk;
        }

        public void setGeometry(List<LineString> geometry) {
            this.geometry = geometry;
        }



        public List<LineString> getRailWayLWGeometry() {
            List<LineString> geometries = new ArrayList<>();


            boolean even = false;
            if (nbTrack % 2 == 0) even = true;

            if (nbTrack == 1) {
                geometries.addAll(getGeometry());
                return geometries;
            }else {

                if (even) {
                    for (int j=0; j < nbTrack/2 ; j++){
                        for (LineString subGeom : getGeometry()) {
                            geometries.add( MakeParallelLine(subGeom, ( distance / 2) + distance * j));
                            geometries.add(MakeParallelLine(subGeom, -((distance / 2) + distance * j)));
                        }
                    }
                } else {
                    for (int j=1; j <= ((nbTrack-1)/2) ; j++) {
                        for (LineString subGeom : getGeometry()) {
                            geometries.add( MakeParallelLine(subGeom,  distance * j));
                            geometries.add(MakeParallelLine(subGeom, -( distance * j)));
                        }
                    }
                    LineMerger centerLine = new LineMerger();
                    centerLine.add(getGeometry());
                    geometries.addAll(centerLine.getMergedLineStrings());
                }
                return geometries;
            }
        }

    }
}


