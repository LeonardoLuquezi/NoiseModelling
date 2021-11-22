/*
 * NoiseMap is a scientific computation plugin for OrbisGIS developed in order to
 * evaluate the noise impact on urban mobility plans. This model is
 * based on the French standard method NMPB2008. It includes traffic-to-noise
 * sources evaluation and sound propagation processing.
 * <p>
 * This version is developed at French IRSTV Institute and at IFSTTAR
 * (http://www.ifsttar.fr/) as part of the Eval-PDU project, funded by the
 * French Agence Nationale de la Recherche (ANR) under contract ANR-08-VILL-0005-01.
 * <p>
 * Noisemap is distributed under GPL 3 license. Its reference contact is Judicaël
 * Picaut <judicael.picaut@ifsttar.fr>. It is maintained by Nicolas Fortin
 * as part of the "Atelier SIG" team of the IRSTV Institute <http://www.irstv.fr/>.
 * <p>
 * Copyright (C) 2011 IFSTTAR
 * Copyright (C) 2011-2012 IRSTV (FR CNRS 2488)
 * <p>
 * Noisemap is free software: you can redistribute it and/or modify it under the
 * terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version.
 * <p>
 * Noisemap is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License along with
 * Noisemap. If not, see <http://www.gnu.org/licenses/>.
 * <p>
 * For more information, please consult: <http://www.orbisgis.org/>
 * or contact directly:
 * info_at_ orbisgis.org
 */
package org.noise_planet.noisemodelling.pathfinder;

import org.apache.commons.math3.geometry.euclidean.threed.Line;
import org.apache.commons.math3.geometry.euclidean.threed.Plane;
import org.h2gis.api.ProgressVisitor;
import org.locationtech.jts.algorithm.*;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;
import org.locationtech.jts.index.ItemVisitor;
import org.locationtech.jts.math.Vector3D;
import org.locationtech.jts.triangulate.quadedge.Vertex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static java.lang.Math.*;
import static org.noise_planet.noisemodelling.pathfinder.ComputeCnossosRays.ComputationSide.LEFT;
import static org.noise_planet.noisemodelling.pathfinder.ComputeCnossosRays.ComputationSide.RIGHT;
import static org.noise_planet.noisemodelling.pathfinder.PointPath.POINT_TYPE.*;
import static org.noise_planet.noisemodelling.pathfinder.ProfileBuilder.IntersectionType.*;
import static org.noise_planet.noisemodelling.pathfinder.utils.AcousticPropagation.getADiv;
import static org.noise_planet.noisemodelling.pathfinder.utils.GeometryUtils.projectPointOnLine;
import static org.noise_planet.noisemodelling.pathfinder.utils.PowerUtils.*;

/**
 * @author Nicolas Fortin
 * @author Pierre Aumond
 * @author Sylvain Palominos
 */
public class ComputeCnossosRays {
    private static final double ALPHA0 = 2e-4;
    private static final double wideAngleTranslationEpsilon = 0.01;
    private static final double epsilon = 1e-7;
    private static final double MAX_RATIO_HULL_DIRECT_PATH = 4;
    private static final Logger LOGGER = LoggerFactory.getLogger(ComputeCnossosRays.class);

    /** Propagation data to use for computation. */
    private final CnossosPropagationData data;

    /** Number of thread used for ray computation. */
    private int threadCount ;

    /**
     * Create new instance from the propagation data.
     * @param data Propagation data used for ray computation.
     */
    public ComputeCnossosRays (CnossosPropagationData data) {
        this.data = data;
        this.threadCount = Runtime.getRuntime().availableProcessors();
    }

    /**
     * Sets the number of thread to use.
     * @param threadCount Number of thread.
     */
    public void setThreadCount(int threadCount) {
        this.threadCount = threadCount;
    }

    /**
     * Run computation and store the results in the given output.
     * @param computeRaysOut Result output.
     */
    public void run(IComputeRaysOut computeRaysOut) {
        ProgressVisitor visitor = data.cellProg;
        ThreadPool threadManager = new ThreadPool(threadCount, threadCount + 1, Long.MAX_VALUE, TimeUnit.SECONDS);
        int maximumReceiverBatch = (int) Math.ceil(data.receivers.size() / (double) threadCount);
        int endReceiverRange = 0;
        //Launch execution of computation by batch
        while (endReceiverRange < data.receivers.size()) {
            //Break if the progress visitor is cancelled
            if (visitor != null && visitor.isCanceled()) {
                break;
            }
            int newEndReceiver = Math.min(endReceiverRange + maximumReceiverBatch, data.receivers.size());
            RangeReceiversComputation batchThread = new RangeReceiversComputation(endReceiverRange, newEndReceiver,
                    this, visitor, computeRaysOut, data);
            if (threadCount != 1) {
                threadManager.executeBlocking(batchThread);
            } else {
                batchThread.run();
            }
            endReceiverRange = newEndReceiver;
        }
        //Once the execution ends, shutdown the thread manager and await termination
        threadManager.shutdown();
        try {
            if(!threadManager.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("Timeout elapsed before termination.");
            }
        } catch (InterruptedException ex) {
            LOGGER.error(ex.getLocalizedMessage(), ex);
        }
    }

    /**
     * Compute the rays to the given receiver.
     * @param rcv     Receiver point.
     * @param dataOut Computation output.
     * @param visitor Progress visitor used for cancellation and progression managing.
     */
    private void computeRaysAtPosition(ReceiverPointInfo rcv, IComputeRaysOut dataOut, ProgressVisitor visitor) {
        //Compute the source search area
        double searchSourceDistance = data.maxSrcDist;
        Envelope receiverSourceRegion = new Envelope(
                rcv.getCoord().x - searchSourceDistance,
                rcv.getCoord().x + searchSourceDistance,
                rcv.getCoord().y - searchSourceDistance,
                rcv.getCoord().y + searchSourceDistance
        );
        Iterator<Integer> regionSourcesLst = data.sourcesIndex.query(receiverSourceRegion);
        List<SourcePointInfo> sourceList = new ArrayList<>();
        //Already processed Raw source (line and/or points)
        HashSet<Integer> processedLineSources = new HashSet<>();
        // Sum of all sources power using only geometric dispersion with direct field
        double totalPowerRemaining = 0;
        while (regionSourcesLst.hasNext()) {
            Integer srcIndex = regionSourcesLst.next();
            if (!processedLineSources.contains(srcIndex)) {
                processedLineSources.add(srcIndex);
                Geometry source = data.sourceGeometries.get(srcIndex);
                double[] wj = data.getMaximalSourcePower(srcIndex);
                if (source instanceof Point) {
                    Coordinate ptpos = source.getCoordinate();
                    if (ptpos.distance(rcv.getCoord()) < data.maxSrcDist) {
                        Orientation orientation = null;
                        if(data.sourcesPk.size() > srcIndex) {
                            orientation = data.sourceOrientation.get(data.sourcesPk.get(srcIndex));
                        }
                        if(orientation == null) {
                            orientation = new Orientation(0,0, 0);
                        }
                        totalPowerRemaining += insertPtSource((Point) source, rcv.getCoord(), srcIndex, sourceList, wj, 1., orientation);
                    }
                } else if (source instanceof LineString) {
                    totalPowerRemaining += addLineSource((LineString) source, rcv.getCoord(), srcIndex, sourceList, wj);
                } else if (source instanceof MultiLineString) {
                    for (int id = 0; id < source.getNumGeometries(); id++) {
                        Geometry subGeom = source.getGeometryN(id);
                        if (subGeom instanceof LineString) {
                            totalPowerRemaining += addLineSource((LineString) subGeom, rcv.getCoord(), srcIndex, sourceList, wj);
                        }
                    }
                } else {
                    throw new IllegalArgumentException(
                            String.format("Sound source %s geometry are not supported", source.getGeometryType()));
                }
            }
        }
        List<SourcePointInfo> newList = new ArrayList<>();
        for(SourcePointInfo src : sourceList) {
            boolean hasPt = false;
            for(SourcePointInfo s : newList) {
                if(s.getCoord().x == src.getCoord().x && s.getCoord().y == src.getCoord().y){
                    hasPt = true;
                }
            }
            if(!hasPt) {
                newList.add(src);
            }
        }
        // Sort sources by power contribution descending
        Collections.sort(sourceList);
        double powerAtSource = 0;
        // For each Pt Source - Pt Receiver
        for (SourcePointInfo src : sourceList) {
            double[] power = rcvSrcPropagation(src, src.li, rcv, dataOut);
            double global = sumArray(power.length, dbaToW(power));
            totalPowerRemaining -= src.globalWj;
            if (power.length > 0) {
                powerAtSource += global;
            } else {
                powerAtSource += src.globalWj;
            }
            totalPowerRemaining = max(0, totalPowerRemaining);
            // If the delta between already received power and maximal potential power received is inferior than than data.maximumError
            if ((visitor != null && visitor.isCanceled()) || (data.maximumError > 0 &&
                            wToDba(powerAtSource + totalPowerRemaining) - wToDba(powerAtSource) < data.maximumError)) {
                break; //Stop looking for more rays
            }
        }
        // No more rays for this receiver
        dataOut.finalizeReceiver(rcv.getId());
    }

    /**
     * Calculation of the propagation between the given source and receiver. The result is registered in the given
     * output.
     * @param src     Source point.
     * @param srcLi   Source power per meter coefficient.
     * @param rcv     Receiver point.
     * @param dataOut Output.
     * @return
     */
    private double[] rcvSrcPropagation(SourcePointInfo src, double srcLi,
                                         ReceiverPointInfo rcv, IComputeRaysOut dataOut) {

        List<PropagationPath> propagationPaths = new ArrayList<>();
        double propaDistance = src.getCoord().distance(rcv.getCoord());
        if (propaDistance < data.maxSrcDist) {
            propagationPaths.addAll(directPath(src, rcv));
            // Process specular reflection
            if (data.reflexionOrder > 0) {
                List<PropagationPath> propagationPaths_all = computeReflexion(rcv.getCoord(), src.getCoord(), false);
                propagationPaths.addAll(propagationPaths_all);
            }
        }
        if (propagationPaths.size() > 0) {
            return dataOut.addPropagationPaths(src.getId(), srcLi, rcv.getId(), propagationPaths);
        }
        return new double[0];
    }

    /**
     * Direct Path computation.
     * @param src Source point.
     * @param rcv Receiver point.
     * @return Calculated propagation paths.
     */
    public List<PropagationPath> directPath(SourcePointInfo src,
                                            ReceiverPointInfo rcv) {
        return directPath(src.getCoord(), src.getId(), src.getOrientation(), rcv.getCoord(), rcv.getId());
    }

    /**
     * Direct Path computation.
     * @param srcCoord Source point coordinate.
     * @param srcId    Source point identifier.
     * @param rcvCoord Receiver point coordinate.
     * @param rcvId    Receiver point identifier.
     * @return Calculated propagation paths.
     */
    public List<PropagationPath> directPath(Coordinate srcCoord, int srcId, Orientation orientation, Coordinate rcvCoord, int rcvId) {
        List<PropagationPath> propagationPaths = new ArrayList<>();
        ProfileBuilder.CutProfile cutProfile = data.profileBuilder.getProfile(srcCoord, rcvCoord, data.gS);
        //If the field is free, simplify the computation
        boolean freeField = cutProfile.isFreeField();
        if(freeField) {
            propagationPaths.add(computeFreeField(cutProfile, data));
        }
        else if(data.isComputeDiffraction()) {
            if (data.isComputeHEdgeDiffraction()) {
                PropagationPath propagationPath = computeHEdgeDiffraction(cutProfile, data.gS);
                propagationPaths.add(propagationPath);
            }
            if (data.isComputeVEdgeDiffraction()) {
                PropagationPath propagationPath = computeVEdgeDiffraction(srcCoord, rcvCoord, data, LEFT);
                if (propagationPath.getPointList() != null) {
                    propagationPaths.add(propagationPath);
                }
                propagationPath = computeVEdgeDiffraction(srcCoord, rcvCoord, data, RIGHT);
                if (propagationPath.getPointList() != null) {
                    propagationPaths.add(propagationPath);
                }
            }
        }

        for(PropagationPath propagationPath : propagationPaths) {
            propagationPath.idSource = srcId;
            propagationPath.idReceiver = rcvId;
            propagationPath.setSourceOrientation(orientation);
        }

        return propagationPaths;
    }

    private static double toCurve(double mn, double d){
        return 2*max(1000, 8*d)* asin(mn/(2*max(1000, 8*d)));
    }

    private static SegmentPath computeSegment(Coordinate src, Coordinate rcv, double[] meanPlane, double gPath, double gS) {
        SegmentPath seg = new SegmentPath();
        Coordinate srcMeanPlane = projectPointOnLine(src, meanPlane[0], meanPlane[1]);
        Coordinate rcvMeanPlane = projectPointOnLine(rcv, meanPlane[0], meanPlane[1]);

        seg.s = src;
        seg.r = rcv;
        seg.sMeanPlane = srcMeanPlane;
        seg.rMeanPlane = rcvMeanPlane;
        seg.sPrime = new Coordinate(seg.s.x+(seg.sMeanPlane.x-seg.s.x)*2, seg.s.y+(seg.sMeanPlane.y-seg.s.y)*2);
        seg.rPrime = new Coordinate(seg.r.x+(seg.rMeanPlane.x-seg.r.x)*2, seg.r.y+(seg.rMeanPlane.y-seg.r.y)*2);
        seg.d = new LineSegment(src, rcv).getLength();
        seg.dp = new LineSegment(srcMeanPlane, rcvMeanPlane).getLength();
        seg.zsH = new LineSegment(src, srcMeanPlane).getLength();
        seg.zrH = new LineSegment(rcv, rcvMeanPlane).getLength();
        seg.a = meanPlane[0];
        seg.b = meanPlane[1];
        seg.testFormH = seg.dp/(30*(seg.zsH +seg.zrH));
        seg.gPath = gPath;
        seg.gPathPrime = seg.testFormH <= 1 ? seg.gPath*(seg.testFormH) + gS*(1-seg.testFormH) : seg.gPath;
        double deltaZT = 6e-3 * seg.dp / (seg.zsH + seg.zrH);
        double deltaZS = ALPHA0 * Math.pow((seg.zsH / (seg.zsH + seg.zrH)), 2) * (seg.dp*seg.dp / 2);
        seg.zsF = seg.zsH + deltaZS + deltaZT;
        double deltaZR = ALPHA0 * Math.pow((seg.zrH / (seg.zsH + seg.zrH)), 2) * (seg.dp*seg.dp / 2);
        seg.zrF = seg.zrH + deltaZR + deltaZT;
        seg.testFormF = seg.dp/(30*(seg.zsF +seg.zrF));

        return seg;
    }

    private static List<Coordinate> computeGroundPts(ProfileBuilder.CutProfile cutProfile, CnossosPropagationData data) {
        List<Coordinate> pts2D = cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .map(ProfileBuilder.CutPoint::getCoordinate)
                .collect(Collectors.toList());
        pts2D = JTSUtility.getNewCoordinateSystem(pts2D);
        List<Coordinate> toRemove = new ArrayList<>();
        for(int i=1; i<pts2D.size(); i++) {
            if(pts2D.get(i).x == pts2D.get(i-1).x) {
                toRemove.add(pts2D.get(i));
            }
        }
        pts2D.removeAll(toRemove);

        List<Coordinate> pts2DGround = new ArrayList<>();
        for(int i=0; i<pts2D.size(); i++) {
            Coordinate c = new Coordinate(pts2D.get(i));
            if(i==0) {
                c = new Coordinate(pts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getSource()));
            }
            else if(i == pts2D.size()-1) {
                c = new Coordinate(pts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getReceiver()));
            }
            pts2DGround.add(c);
        }
        return pts2DGround;
    }

    private static List<Coordinate> computePts2D(ProfileBuilder.CutProfile cutProfile) {
        List<Coordinate> pts2D = cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .map(ProfileBuilder.CutPoint::getCoordinate)
                .collect(Collectors.toList());
        pts2D = JTSUtility.getNewCoordinateSystem(pts2D);
        List<Coordinate> toRemove = new ArrayList<>();
        /*for(int i=1; i<pts2D.size(); i++) {
            if(pts2D.get(i).x == pts2D.get(i-1).x) {
                toRemove.add(pts2D.get(i));
            }
        }*/
        pts2D.removeAll(toRemove);
        return pts2D;
    }

    private static List<Coordinate> computePts2DGround(ProfileBuilder.CutProfile cutProfile, CnossosPropagationData data) {
        List<Coordinate> pts2D = cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .map(cut -> new Coordinate(cut.getCoordinate().x, cut.getCoordinate().y, data.profileBuilder.getZGround(cut)))
                .collect(Collectors.toList());
        pts2D = JTSUtility.getNewCoordinateSystem(pts2D);
        List<Coordinate> toRemove = new ArrayList<>();
        for(int i=1; i<pts2D.size(); i++) {
            if(pts2D.get(i).x == pts2D.get(i-1).x) {
                toRemove.add(pts2D.get(i));
            }
        }
        for(Coordinate c : toRemove) {
            pts2D.remove(c);
        }

        List<Coordinate> pts2DGround = new ArrayList<>();
        for(int i=0; i<pts2D.size(); i++) {
            Coordinate c = new Coordinate(pts2D.get(i));
            if(i==0) {
                c = new Coordinate(pts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getSource()));
            }
            else if(i == pts2D.size()-1) {
                c = new Coordinate(pts2D.get(i).x, data.profileBuilder.getZGround(cutProfile.getReceiver()));
            }
            pts2DGround.add(c);
        }
        return pts2DGround;
    }

    /**
     * Compute the propagation in case of free field.
     * @param cutProfile CutProfile containing all the data for propagation computation.
     * @return The calculated propagation path.
     */
    public PropagationPath computeFreeField(ProfileBuilder.CutProfile cutProfile, CnossosPropagationData data) {
        ProfileBuilder.CutPoint srcCut = cutProfile.getSource();
        ProfileBuilder.CutPoint rcvCut = cutProfile.getReceiver();

        List<ProfileBuilder.CutPoint> cuts = cutProfile.getCutPoints().stream()
                .filter(cut -> cut.getType() != GROUND_EFFECT)
                .collect(Collectors.toList());
        List<Coordinate> pts2DGround = computePts2DGround(cutProfile, data);
        Coordinate src = new Coordinate(pts2DGround.get(0));
        src.y = srcCut.getCoordinate().z;
        Coordinate rcv = new Coordinate(pts2DGround.get(pts2DGround.size()-1));
        rcv.y = rcvCut.getCoordinate().z;
        double[] meanPlane;

        double firstY = pts2DGround.get(0).y;
        if (pts2DGround.stream().allMatch(c -> c.y == firstY)) {
            meanPlane = new double[]{0, 0};
        } else {
            meanPlane = JTSUtility.getMeanPlaneCoefficients(pts2DGround.toArray(new Coordinate[0]));
        }
        SegmentPath srSeg = computeSegment(src, rcv, meanPlane, cutProfile.getGPath(srcCut, rcvCut), srcCut.getGroundCoef());
        LineSegment dSR = new LineSegment(src, rcv);

        List<SegmentPath> segments = new ArrayList<>();

        List<PointPath> points = new ArrayList<>();
        PointPath srcPP = new PointPath(src, data.profileBuilder.getZGround(srcCut), srcCut.getGroundCoef(), srcCut.getWallAlpha(), PointPath.POINT_TYPE.SRCE);
        srcPP.buildingId = srcCut.getBuildingId();
        srcPP.wallId = srcCut.getWallId();
        points.add(srcPP);

        PropagationPath propagationPath = new PropagationPath(false, points, segments, srSeg);
        if(data.isComputeDiffraction()) {
            //Check for Rayleigh criterion for segments computation
            // Compute mean ground plan
            for (int iO = 1; iO < pts2DGround.size() - 1; iO++) {
                Coordinate o = pts2DGround.get(iO);

                double dSO = new LineSegment(src, o).getLength();
                double dOR = new LineSegment(o, rcv).getLength();

                PointPath pO = new PointPath(o, o.z, srcCut.getGroundCoef(), new ArrayList<>(), DIFH_RCRIT);
                propagationPath.deltaH = dSR.orientationIndex(o) * (dSO + dOR - srSeg.d);
                if(dSR.orientationIndex(o) == 1) {
                    propagationPath.deltaF = toCurve(dSO, srSeg.d) + toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                }
                else {
                    Coordinate pA = dSR.pointAlong((o.x-src.x)/(rcv.x-src.x));
                    propagationPath.deltaF =2*toCurve(new LineSegment(src, pA).getLength(), srSeg.d) + 2*toCurve(new LineSegment(pA, rcv).getLength(), srSeg.d) - toCurve(dSO, srSeg.d) - toCurve(dOR, srSeg.d) - toCurve(srSeg.d, srSeg.d);
                }
                List<Integer> validFreq = new ArrayList<>(Arrays.asList(63, 125, 250, 500, 1000, 2000, 4000, 8000))
                        .stream()
                        .filter(f -> propagationPath.deltaH > -(340./f) / 20)
                        .collect(Collectors.toList());
                if (!validFreq.isEmpty()) {
                    //Add point path

                    //Plane S->O
                    Coordinate[] soCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), 0, iO + 1);
                    double[] abs = new double[]{0, 0};
                    double soY = soCoords[0].y;
                    if (!Arrays.stream(soCoords).allMatch(c -> c.y == soY)) {
                        abs = JTSUtility.getMeanPlaneCoefficients(soCoords);
                    }
                    SegmentPath seg1 = computeSegment(src, o, abs, cutProfile.getGPath(srcCut, cuts.get(iO)), srcCut.getGroundCoef());

                    LineSegment sPrimeR = new LineSegment(seg1.sPrime, rcv);
                    LineSegment sPrimeO = new LineSegment(seg1.sPrime, o);
                    propagationPath.deltaSPrimeRH = sPrimeR.orientationIndex(o)*(sPrimeO.getLength() + dOR - sPrimeR.getLength());

                    //Plane O->R
                    Coordinate[] orCoords = Arrays.copyOfRange(pts2DGround.toArray(new Coordinate[0]), iO, pts2DGround.size());
                    double[] abr = new double[]{0, 0};
                    double orY = orCoords[0].y;
                    if (!Arrays.stream(soCoords).allMatch(c -> c.y == orY)) {
                        abr = JTSUtility.getMeanPlaneCoefficients(orCoords);
                    }
                    SegmentPath seg2 = computeSegment(o, rcv, abr, cutProfile.getGPath(cuts.get(iO), rcvCut), srcCut.getGroundCoef());

                    LineSegment sRPrime = new LineSegment(src, seg2.rPrime);
                    LineSegment oRPrime = new LineSegment(o, seg2.rPrime);
                    propagationPath.deltaSRPrimeH = sRPrime.orientationIndex(o)*(dSO + oRPrime.getLength() - sRPrime.getLength());


                    Coordinate srcPrime = new Coordinate(src.x + (seg1.sMeanPlane.x - src.x) * 2, src.y + (seg1.sMeanPlane.y - src.y) * 2);
                    Coordinate rcvPrime = new Coordinate(rcv.x + (seg2.rMeanPlane.x - rcv.x) * 2, rcv.y + (seg2.rMeanPlane.y - rcv.y) * 2);

                    LineSegment dSPrimeRPrime = new LineSegment(srcPrime, rcvPrime);
                    srSeg.dPrime = dSPrimeRPrime.getLength();
                    seg1.dPrime = new LineSegment(srcPrime, o).getLength();
                    seg2.dPrime = new LineSegment(o, rcvPrime).getLength();

                    propagationPath.deltaPrimeH = dSPrimeRPrime.orientationIndex(o) * (seg1.dPrime + seg2.dPrime - srSeg.dPrime);
                    validFreq = new ArrayList<>(Arrays.asList(63, 125, 250, 500, 1000, 2000, 4000, 8000))
                            .stream()
                            .filter(f -> propagationPath.deltaH > (340./f) / 4 - propagationPath.deltaPrimeH)
                            .collect(Collectors.toList());
                    if (!validFreq.isEmpty()) {
                        segments.add(seg1);
                        segments.add(seg2);
                        points.add(pO);
                        propagationPath.difHPoints.add(points.size() - 1);
                    }
                }
            }
        }
        if(segments.isEmpty()) {
            segments.add(srSeg);
        }
        PointPath rcvPP = new PointPath(rcv, data.profileBuilder.getZGround(rcvCut), rcvCut.getGroundCoef(), rcvCut.getWallAlpha(), PointPath.POINT_TYPE.RECV);
        rcvPP.buildingId = rcvCut.getBuildingId();
        rcvPP.wallId = rcvCut.getWallId();
        points.add(rcvPP);

        return propagationPath;
    }

    /**
     * Compute horizontal diffraction (diffraction of vertical edge.)
     * @param rcvCoord Receiver coordinates.
     * @param srcCoord Source coordinates.
     * @param data     Propagation data.
     * @param side     Side to compute.
     * @return The propagation path of the horizontal diffraction.
     */
    public PropagationPath computeVEdgeDiffraction(Coordinate rcvCoord, Coordinate srcCoord,
                                                   CnossosPropagationData data, ComputationSide side) {

        PropagationPath path = null;
        List<Coordinate> coordinates = computeSideHull(side != LEFT, new Coordinate(rcvCoord), new Coordinate(srcCoord), data.profileBuilder);
        List<Coordinate> coords = toDirectLine(coordinates);

        if (!coordinates.isEmpty()) {
            if (coordinates.size() > 2) {
                List<SegmentPath> freePaths = new ArrayList<>();
                List<Coordinate> topoPts = new ArrayList<>();
                topoPts.add(coordinates.get(0));
                for(int i=0; i<coordinates.size()-1; i++) {
                    ProfileBuilder.CutProfile profile = data.profileBuilder.getProfile(coordinates.get(i), coordinates.get(i+1), data.gS);
                    topoPts.addAll(profile.getCutPoints().stream()
                            .filter(cut -> cut.getType().equals(BUILDING) || cut.getType().equals(TOPOGRAPHY) || cut.getType().equals(RECEIVER))
                            .map(ProfileBuilder.CutPoint::getCoordinate)
                            .collect(Collectors.toList()));
                    freePaths.addAll(computeFreeField(profile, data).getSegmentList());
                }
                //Filter bridge
                List<Integer> toRemove = new ArrayList<>();
                for(int i=0; i<coordinates.size()-1; i++) {
                    Coordinate c0 = coordinates.get(i);
                    Coordinate c1 = coordinates.get(i+1);
                    boolean between = false;
                    for(int j=0; j<topoPts.size(); j++) {
                        Coordinate p = topoPts.get(j);
                        if(p.equals(c1)) {
                            break;
                        }
                        if(p.equals(c0)) {
                            between = true;
                        }
                        if(between && p.z > c1.z) {
                            toRemove.add(j);
                        }
                    }
                }
                Collections.sort(toRemove);
                Collections.reverse(toRemove);
                for(int i : toRemove) {
                    topoPts.remove(i);
                }
                //Set z value
                for(int i=0; i<topoPts.size(); i++) {
                    final Coordinate pt = topoPts.get(i);
                    coordinates.forEach(c -> {
                        if(c.equals(pt) && c.z == pt.z) {
                            pt.z = data.profileBuilder.getZGround(pt);
                        }
                    });
                }
                //Filter same pts
                toRemove = new ArrayList<>();
                for(int i=0; i<topoPts.size()-1; i++) {
                    Coordinate pi0 = topoPts.get(i);
                    Coordinate pi1 = topoPts.get(i+1);
                    if(pi0.equals(pi1) && pi0.z == pi1.z) {
                        toRemove.add(i);
                    }
                }
                Collections.sort(toRemove);
                Collections.reverse(toRemove);
                for(int i : toRemove) {
                    topoPts.remove(i);
                }

                List<Coordinate> groundPts = toDirectLine(topoPts);
                double g = 0;
                double d = 0;
                for(SegmentPath seg : freePaths) {
                    d+=seg.d;
                }
                for(SegmentPath seg : freePaths) {
                    g+=seg.gPath*seg.d/d;
                }
                PointPath src = new PointPath(coords.get(0), data.profileBuilder.getZ(coordinates.get(0)), data.gS, new ArrayList<>(), SRCE);
                PointPath rcv = new PointPath(coords.get(coords.size()-1), data.profileBuilder.getZ(coordinates.get(coordinates.size()-1)), data.gS, new ArrayList<>(), RECV);
                double[] meanPlan = JTSUtility.getMeanPlaneCoefficients(groundPts.toArray(new Coordinate[0]));
                SegmentPath srSeg = computeSegment(src.coordinate, rcv.coordinate, meanPlan, g, data.gS);
                srSeg.dc = sqrt(pow(rcvCoord.x-srcCoord.x, 2) + pow(rcvCoord.y-srcCoord.y, 2) + pow(rcvCoord.z-srcCoord.z, 2));

                List<PointPath> pps = new ArrayList<>();
                pps.add(src);
                PointPath previous = src;
                List<SegmentPath> segs = new ArrayList<>();
                path = new PropagationPath(false, pps, segs, srSeg);
                double e = 0;
                for(int i=1; i<coordinates.size()-1; i++) {
                    PointPath diff = new PointPath(coords.get(i), data.profileBuilder.getZ(coordinates.get(i)), data.gS, new ArrayList<>(), DIFV);
                    pps.add(diff);
                    path.difVPoints.add(i);
                    SegmentPath seg = computeSegment(previous.coordinate, diff.coordinate, meanPlan, g, data.gS);
                    segs.add(seg);
                    if(i>1) {
                        e += seg.d;
                    }
                    previous = diff;
                }
                segs.add(computeSegment(previous.coordinate, coords.get(coords.size()-1), meanPlan, g, data.gS));
                pps.add(rcv);
                path.deltaH = segs.get(0).d + e + segs.get(segs.size()-1).d - srSeg.dc;
                path.e = e;
                path.difVPoints.add(1);
            }
        }
        return path;
    }

    private List<Coordinate> toDirectLine(List<Coordinate> coordinates) {
        List<Coordinate> coords = new ArrayList<>();
        Coordinate prev = coordinates.get(0);
        double d = 0;
        for(Coordinate c : coordinates) {
            d+=new LineSegment(prev, c).getLength();
            prev = c;
            coords.add(new Coordinate(d, c.z));
        }
        return coords;
    }

    private List<ProfileBuilder.Wall> getHullWalls(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder, List<Integer> usedWall) {
        List<ProfileBuilder.Wall> hull = new ArrayList<>();
        if (p1.equals(p2)) {
            return hull;
        }
        //hull.add(p1);
        ProfileBuilder.CutProfile profile = profileBuilder.getProfile(p1, p2);
        if(profile.isFreeField()) {
            //hull.add(p2);
            return hull;
        }
        //TODO manage case of toutching walls
        List<ProfileBuilder.CutPoint> cuts = profile.getCutPoints().stream().filter(cut -> cut.getType().equals(WALL) || cut.getType().equals(BUILDING)).collect(Collectors.toList());

        ProfileBuilder.CutPoint cut = null;
        for(ProfileBuilder.CutPoint c : cuts) {
            if(!usedWall.contains(c.getWallId())) {
                cut = c;
                break;
            }
        }
        usedWall.add(cut.getWallId());
        switch(cut.getType()) {
            case WALL:
                ProfileBuilder.Wall w = profileBuilder.getWall(cut.getWallId());
                hull.add(w);
                boolean useP0 = new LineSegment(p1, p2).orientationIndex(w.getLine().p0) == (left ? -1 : 1);
                hull.addAll(getHullWalls(left, useP0 ? w.getLine().p0 : w.getLine().p1, p2, profileBuilder, usedWall));
                break;
            case BUILDING:
                w = profileBuilder.getProcessedWalls().get(cut.getWallId());
                hull.add(w);
                useP0 = new LineSegment(p1, p2).orientationIndex(w.getLine().p0) == (left ? -1 : 1);
                Coordinate c = new Coordinate(useP0 ? w.getLine().p0 : w.getLine().p1);
                c.z = profileBuilder.getZGround(c);
                hull.addAll(getHullWalls(left, c, p2, profileBuilder, usedWall));
                break;
        }
        return hull;
    }

    public PropagationPath computeHEdgeDiffraction(ProfileBuilder.CutProfile cutProfile, double gs) {
        List<SegmentPath> segments = new ArrayList<>();
        List<PointPath> points = new ArrayList<>();
        List<ProfileBuilder.CutPoint> cutPts = cutProfile.getCutPoints().stream()
                .filter(cutPoint -> cutPoint.getType() != GROUND_EFFECT)
                .collect(Collectors.toList());
        List<Coordinate> pts2D = computePts2D(cutProfile);
        double[] meanPlane = JTSUtility.getMeanPlaneCoefficients(pts2D.toArray(new Coordinate[0]));
        SegmentPath srPath = computeSegment(pts2D.get(0), pts2D.get(pts2D.size()-1), meanPlane, cutProfile.getGPath(), cutProfile.getSource().getGroundCoef());

        PropagationPath propagationPath = new PropagationPath(true, points, segments, srPath);

        LineSegment srcRcvLine = new LineSegment(pts2D.get(0), pts2D.get(pts2D.size()-1));
        List<Coordinate> pts = new ArrayList<>();
        pts.add(pts2D.get(0));
        for(int i=1; i<pts2D.size(); i++) {
            Coordinate pt = pts2D.get(i);
            double frac = srcRcvLine.segmentFraction(pt);
            double y = 0.0;
            for(int j=i+1; j<pts2D.size(); j++) {
                y = max(y, srcRcvLine.p0.y + frac*(pts2D.get(j).y-srcRcvLine.p0.y));
            }
            if(y <= pt.y){
                pts.add(pt);
                srcRcvLine = new LineSegment(pt, pts2D.get(pts2D.size()-1));

                //Filter point to only keep hull.
                List<Coordinate> toRemove = new ArrayList<>();
                //check if last-1 point is under or not the surrounding points
                for(int j = pts.size()-2; j > 0; j--) {
                    if(pts.get(j).y==Double.MAX_VALUE || Double.isInfinite(pts.get(j).y)) {
                        toRemove.add(pts.get(j));
                    }
                    //line between last point and previous-1 point
                    else {
                        LineSegment lineRm = new LineSegment(pts.get(j - 1), pt);
                        Coordinate cRm = pts.get(j);
                        double fracRm = lineRm.segmentFraction(cRm);
                        double zRm = lineRm.p0.z + fracRm * (lineRm.p1.z - lineRm.p0.z);
                        if (zRm >= cRm.z) {
                            toRemove.add(pts.get(j));
                        }
                    }
                }
                pts.removeAll(toRemove);
            }
        }

        double e = 0;
        for (int i = 1; i < pts.size(); i++) {
            int i0 = pts2D.indexOf(pts.get(i-1));
            int i1 = pts2D.indexOf(pts.get(i));
            ProfileBuilder.CutProfile profile = data.profileBuilder.getProfile(cutPts.get(i0), cutPts.get(i1));
            List<Coordinate> subList = pts2D.subList(i0, i1+1).stream().map(Coordinate::new).collect(Collectors.toList());
            for(int j=0; j<=i1-i0; j++){
                if(!cutPts.get(j+i0).getType().equals(BUILDING)){
                    subList.get(j).y = data.profileBuilder.getZGround(cutPts.get(j+i0));
                }
            }
            meanPlane = JTSUtility.getMeanPlaneCoefficients(subList.toArray(new Coordinate[0]));
            SegmentPath path = computeSegment(pts2D.get(i0), pts2D.get(i1), meanPlane, profile.getGPath(), profile.getSource().getGroundCoef());
            segments.add(path);
            if(points.isEmpty()) {
                points.add(new PointPath(path.s,  data.profileBuilder.getZGround(path.s), cutPts.get(i0).getGroundCoef(), cutPts.get(i0).getWallAlpha(), PointPath.POINT_TYPE.SRCE));
            }
            points.add(new PointPath(path.r,  data.profileBuilder.getZGround(path.r), cutPts.get(i1).getGroundCoef(), cutPts.get(i1).getWallAlpha(), PointPath.POINT_TYPE.RECV));
            if(i != pts.size()-1) {
                if(i != 1) {
                    e += path.d;
                }
                propagationPath.difHPoints.add(i);
                PointPath pt = points.get(points.size()-1);
                pt.type = PointPath.POINT_TYPE.DIFH;
                if(pt.buildingId != -1) {
                    pt.alphaWall = data.profileBuilder.getBuilding(pt.buildingId).getAlphas();
                    pt.setBuildingHeight(data.profileBuilder.getBuilding(pt.buildingId).getHeight());
                }
                else if(pt.wallId != -1) {
                    pt.alphaWall = data.profileBuilder.getWall(pt.wallId).getAlphas();
                    pt.setBuildingHeight(data.profileBuilder.getWall(pt.wallId).getHeight());
                }
            }
        }
        propagationPath.e = e;

        Coordinate src = points.get(0).coordinate;
        Coordinate rcv = points.get(points.size()-1).coordinate;
        PointPath p0 = points.stream().filter(p -> p.type.equals(DIFH)).findFirst().get();
        Coordinate c0 = p0.coordinate;
        PointPath pn = points.stream().filter(p -> p.type.equals(DIFH)).reduce((first, second) -> second).get();
        Coordinate cn = pn.coordinate;

        SegmentPath seg1 = segments.get(0);
        SegmentPath seg2 = segments.get(segments.size()-1);

        double dSO0 = new LineSegment(src,c0).getLength();
        double dOnR = new LineSegment(cn, rcv).getLength();
        LineSegment sr = new LineSegment(src, rcv);

        propagationPath.deltaH = sr.orientationIndex(c0) * (dSO0 + e + dOnR - srPath.d);

        LineSegment sPrimeR = new LineSegment(seg1.sPrime, rcv);
        LineSegment sPrimeO = new LineSegment(seg1.sPrime, c0);
        propagationPath.deltaSPrimeRH = sPrimeR.orientationIndex(c0)*(sPrimeO.getLength() + e + dOnR - sPrimeR.getLength());
        propagationPath.deltaSPrimeRF = toCurve(sPrimeO.getLength(), sPrimeR.getLength()) + toCurve(e, sPrimeR.getLength()) + toCurve(dOnR, sPrimeR.getLength()) - toCurve(sPrimeR.getLength(), sPrimeR.getLength());

        LineSegment sRPrime = new LineSegment(src, seg2.rPrime);
        LineSegment oRPrime = new LineSegment(cn, seg2.rPrime);
        propagationPath.deltaSRPrimeH = (src.x>seg2.rPrime.x?-1:1)*sRPrime.orientationIndex(cn)*(dSO0 + e + oRPrime.getLength() - sRPrime.getLength());
        propagationPath.deltaSRPrimeF = toCurve(dSO0, sRPrime.getLength()) + toCurve(e, sRPrime.getLength()) + toCurve(oRPrime.getLength(), sRPrime.getLength()) - toCurve(sRPrime.getLength(), sRPrime.getLength());

        Coordinate srcPrime = new Coordinate(src.x + (seg1.sMeanPlane.x - src.x) * 2, src.y + (seg1.sMeanPlane.y - src.y) * 2);
        Coordinate rcvPrime = new Coordinate(rcv.x + (seg2.rMeanPlane.x - rcv.x) * 2, rcv.y + (seg2.rMeanPlane.y - rcv.y) * 2);

        LineSegment dSPrimeRPrime = new LineSegment(srcPrime, rcvPrime);
        srPath.dPrime = dSPrimeRPrime.getLength();
        seg1.dPrime = new LineSegment(srcPrime, c0).getLength();
        seg2.dPrime = new LineSegment(cn, rcvPrime).getLength();

        propagationPath.deltaPrimeH = dSPrimeRPrime.orientationIndex(c0) * (seg1.dPrime + e + seg2.dPrime - srPath.dPrime);
        if(sr.orientationIndex(c0) == 1) {
            propagationPath.deltaF = toCurve(seg1.d, srPath.d) + toCurve(e, srPath.d)  + toCurve(seg2.d, srPath.d) - toCurve(srPath.d, srPath.d);
        }
        else {
            Coordinate pA = sr.pointAlong((c0.x-srcPrime.x)/(rcvPrime.x-srcPrime.x));
            propagationPath.deltaF =2*toCurve(new LineSegment(srcPrime, pA).getLength(), srPath.dPrime) + 2*toCurve(new LineSegment(pA, rcvPrime).getLength(), srPath.dPrime) - toCurve(seg1.dPrime, srPath.dPrime) - toCurve(seg2.dPrime, srPath.dPrime) - toCurve(srPath.dPrime, srPath.dPrime);
        }

        return propagationPath;
    }
/*
    private List<Coordinate> rubberBand(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder) {
        List<ProfileBuilder.Wall> walls = getHullWalls(left, p1, p2, profileBuilder, new ArrayList<>());
        List<LineSegment> rotWalls = new ArrayList<>();
        for(ProfileBuilder.Wall w : walls) {
            Coordinate w0 = w.getLine().p0;
            Coordinate c0 = new Coordinate(w0.x*cos(a), w0.z, left ? -w0.y : w0.y);
            Coordinate c0Gr = new Coordinate(c0);
            c0Gr.y = profileBuilder.getZGround(c0);
            Coordinate w1 = w.getLine().p1;
            Coordinate c1 = new Coordinate(w1.x*cos(a), w1.z, left ? -w1.y : w1.y);
            Coordinate c1Gr = new Coordinate(c1);
            c1Gr.y = profileBuilder.getZGround(c1);
            if(!c0.equals(c1)) {
                rotWalls.add(new LineSegment(c0, c1));
            }
            rotWalls.add(new LineSegment(c0, c0Gr));
            rotWalls.add(new LineSegment(c1, c1Gr));
        }
        List<LineSegment> filtered = new ArrayList<>();
        //Filtering
        for(LineSegment ls : rotWalls) {
            boolean valid = true;
            for(LineSegment f : filtered) {
                if(f.p0.equals(ls.p0) && f.p1.equals(ls.p1)) {
                    if(f.p0.z < ls.p0.z) {
                        filtered.remove(f);
                    }
                    else {
                        valid = false;
                    }
                    break;
                }
            }
            if(valid) {
                filtered.add(ls);
            }
        }

        List<Coordinate> coords = new ArrayList<>();
        LineSegment rs = new LineSegment(
                new Coordinate(p1.x, p1.z, left ? -p1.y : p1.y),
                new Coordinate(p2.x, p2.z, left ? -p2.y : p2.y));
        for(LineSegment ls : filtered) {
            Coordinate inter = ls.intersection(rs);
            if(inter != null) {
                coords.add(new Coordinate(inter.x, left ? -ls.p0.z : ls.p0.z, inter.y));
            }
        }
        coords.add(0, new Coordinate(p1));
        coords.add(new Coordinate(p2));

        //Handle concave path
        int size = -1;
        while(size != coords.size()) {
            size = coords.size();
            for (int i = 0; i < coords.size() - 2; i++) {
                LineSegment l02 = new LineSegment(coords.get(i), coords.get(i + 2));
                if (l02.orientationIndex(coords.get(i + 1)) == (left ? 1 : -1)) {
                    double d02 = JTSUtility.dist3D(coords.get(i), coords.get(i + 2));
                    double d01 = JTSUtility.dist3D(coords.get(i), coords.get(i + 1));
                    double d12 = JTSUtility.dist3D(coords.get(i + 1), coords.get(i + 2));
                    if (d01 + d12 > d02*1.01) {
                        coords.remove(i + 1);
                        break;
                    }
                }
            }
        }

        return coords;
    }

    public List<Coordinate> computeSideHull(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder) {
        List<Coordinate> coords = rubberBand(left, p1, p2, profileBuilder);

        double totDist = 0;
        for(int i=1; i< coords.size(); i++) {
            totDist += new LineSegment(coords.get(i-1), coords.get(i)).getLength();
        }
        double z0 = coords.get(0).z;
        double delta = coords.get(coords.size()-1).z-z0;
        double currDist = 0;
        //Z is not calculated in the same way as CNOSSOS which results seem wrong and doesn't seem to use the rubber band methods but just the x axis
        for(int i=1; i< coords.size()-1; i++) {
            currDist += new LineSegment(coords.get(i-1), coords.get(i)).getLength();
            coords.get(i).z = z0 + delta*(currDist/totDist);
        }
        return coords;
    }*/

    /**
     * Compute Side Hull
     * Create a line between p1 and p2. Find the first intersection of this line with a building then create a ConvexHull
     * with the points of buildings in intersection. While there is an intersection add more points to the convex hull.
     * The side diffraction path is found when there is no more intersection.
     *
     * @param left If true return path between p1 and p2; else p2 to p1
     * @param p1   First point
     * @param p2   Second point
     * @return
     */
    public List<Coordinate> computeSideHull(boolean left, Coordinate p1, Coordinate p2, ProfileBuilder profileBuilder) {
        if (p1.equals(p2)) {
            return new ArrayList<>();
        }

        // Intersection test cache
        Set<LineSegment> freeFieldSegments = new HashSet<>();
        GeometryFactory geometryFactory = new GeometryFactory();

        List<Coordinate> input = new ArrayList<>();

        Coordinate[] coordinates = new Coordinate[0];
        int indexp1 = 0;
        int indexp2 = 0;

        boolean convexHullIntersects = true;

        input.add(p1);
        input.add(p2);

        Set<Integer> buildingInHull = new HashSet<>();
        Set<Integer> wallInHull = new HashSet<>();

        Plane cutPlane = computeZeroRadPlane(p1, p2);

        BuildingIntersectionRayVisitor buildingIntersectionRayVisitor = new BuildingIntersectionRayVisitor(
                profileBuilder.getBuildings(), p1, p2, profileBuilder, input, buildingInHull, cutPlane);

        data.profileBuilder.getBuildingsOnPath(p1, p2, buildingIntersectionRayVisitor);

        WallIntersectionRayVisitor wallIntersectionRayVisitor = new WallIntersectionRayVisitor(
                profileBuilder.getWalls(), p1, p2, profileBuilder, input, wallInHull, cutPlane);

        data.profileBuilder.getWallsOnPath(p1, p2, wallIntersectionRayVisitor);

        int k;
        while (convexHullIntersects) {
            ConvexHull convexHull = new ConvexHull(input.toArray(new Coordinate[0]), geometryFactory);
            Geometry convexhull = convexHull.getConvexHull();

            if (convexhull.getLength() / p1.distance(p2) > MAX_RATIO_HULL_DIRECT_PATH) {
                return new ArrayList<>();
            }

            convexHullIntersects = false;
            coordinates = convexhull.getCoordinates();

            input.clear();
            input.addAll(Arrays.asList(coordinates));

            indexp1 = -1;
            for (int i = 0; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p1)) {
                    indexp1 = i;
                    break;
                }
            }
            if (indexp1 == -1) {
                // P1 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            // Transform array to set p1 at index=0
            Coordinate[] coordinatesShifted = new Coordinate[coordinates.length];
            // Copy from P1 to end in beginning of new array
            int len = (coordinates.length - 1) - indexp1;
            System.arraycopy(coordinates, indexp1, coordinatesShifted, 0, len);
            // Copy from 0 to P1 in the end of array
            System.arraycopy(coordinates, 0, coordinatesShifted, len, coordinates.length - len - 1);
            coordinatesShifted[coordinatesShifted.length - 1] = coordinatesShifted[0];
            coordinates = coordinatesShifted;
            indexp1 = 0;
            indexp2 = -1;
            for (int i = 1; i < coordinates.length - 1; i++) {
                if (coordinates[i].equals(p2)) {
                    indexp2 = i;
                    break;
                }
            }
            if (indexp2 == -1) {
                // P2 does not belong to convex vertices, cannot compute diffraction
                // TODO handle concave path
                return new ArrayList<>();
            }
            for (k = 0; k < coordinates.length - 1; k++) {
                LineSegment freeFieldTestSegment = new LineSegment(coordinates[k], coordinates[k + 1]);
                // Ignore intersection if iterating over other side (not parts of what is returned)
                if (left && k < indexp2 || !left && k >= indexp2) {
                    if (!freeFieldSegments.contains(freeFieldTestSegment)) {
                        // Check if we still are in the propagation domain
                        /*if (!profileBuilder.getMeshEnvelope().contains(coordinates[k]) ||
                                !profileBuilder.getMeshEnvelope().contains(coordinates[k + 1])) {
                            // This side goes over propagation path
                            return new ArrayList<>();
                        }*/
                        buildingIntersectionRayVisitor = new BuildingIntersectionRayVisitor(profileBuilder.getBuildings(),
                                coordinates[k], coordinates[k + 1], profileBuilder, input, buildingInHull, cutPlane);
                        profileBuilder.getBuildingsOnPath(coordinates[k], coordinates[k + 1], buildingIntersectionRayVisitor);
                        wallIntersectionRayVisitor = new WallIntersectionRayVisitor(profileBuilder.getWalls(),
                                coordinates[k], coordinates[k + 1], profileBuilder, input, wallInHull, cutPlane);
                        profileBuilder.getWallsOnPath(coordinates[k], coordinates[k + 1], wallIntersectionRayVisitor);
                        if (!buildingIntersectionRayVisitor.doContinue() || !wallIntersectionRayVisitor.doContinue()) {
                            convexHullIntersects = true;
                        }
                        if (!convexHullIntersects) {
                            freeFieldSegments.add(freeFieldTestSegment);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
        // Check for invalid coordinates
        for (Coordinate p : coordinates) {
            if (p.z < 0) {
                return new ArrayList<>();
            }
        }

        if (left) {
            return Arrays.asList(Arrays.copyOfRange(coordinates, indexp1, indexp2 + 1));
        } else {
            List<Coordinate> inversePath = Arrays.asList(Arrays.copyOfRange(coordinates, indexp2, coordinates.length));
            Collections.reverse(inversePath);
            return inversePath;
        }
    }

    public static Plane computeZeroRadPlane(Coordinate p0, Coordinate p1) {
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D s = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p0.x, p0.y, p0.z);
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D r = new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p1.x, p1.y, p1.z);
        double angle = Math.atan2(p1.y - p0.y, p1.x - p0.x);
        // Compute rPrime, the third point of the plane that is at -PI/2 with SR vector
        org.apache.commons.math3.geometry.euclidean.threed.Vector3D rPrime = s.add(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(Math.cos(angle - Math.PI / 2), Math.sin(angle - Math.PI / 2), 0));
        Plane p = new Plane(r, s, rPrime, 1e-6);
        // Normal of the cut plane should be upward
        if (p.getNormal().getZ() < 0) {
            p.revertSelf();
        }
        return p;
    }


    private static final class BuildingIntersectionRayVisitor implements ItemVisitor {
        Set<Integer> itemProcessed = new HashSet<>();
        List<ProfileBuilder.Building> buildings;
        Coordinate p1;
        Coordinate p2;
        LineString seg;
        Set<Integer> buildingsInIntersection;
        ProfileBuilder profileBuilder;
        Plane cutPlane;
        List<Coordinate> input;
        boolean foundIntersection = false;

        public BuildingIntersectionRayVisitor(List<ProfileBuilder.Building> buildings, Coordinate p1,
                                              Coordinate p2, ProfileBuilder profileBuilder, List<Coordinate> input, Set<Integer> buildingsInIntersection, Plane cutPlane) {
            this.profileBuilder = profileBuilder;
            this.input = input;
            this.buildingsInIntersection = buildingsInIntersection;
            this.cutPlane = cutPlane;
            this.buildings = buildings;
            this.p1 = p1;
            this.p2 = p2;
            seg = new LineSegment(p1, p2).toGeometry(new GeometryFactory());
        }

        @Override
        public void visitItem(Object item) {
            int id = (Integer) item;
            if(!itemProcessed.contains(id)) {
                itemProcessed.add(id);
                final ProfileBuilder.Building b = buildings.get(id - 1);
                RectangleLineIntersector rect = new RectangleLineIntersector(b.getGeometry().getEnvelopeInternal());
                if (rect.intersects(p1, p2) && b.getGeometry().intersects(seg)) {
                    addItem(id);
                }
            }
        }

        public void addItem(int id) {
            if (buildingsInIntersection.contains(id)) {
                return;
            }
            List<Coordinate> roofPoints = profileBuilder.getWideAnglePointsByBuilding(id, 0, 2 * Math.PI);
            // Create a cut of the building volume
            roofPoints = cutRoofPointsWithPlane(cutPlane, roofPoints);
            if (!roofPoints.isEmpty()) {
                input.addAll(roofPoints.subList(0, roofPoints.size() - 1));
                buildingsInIntersection.add(id);
                foundIntersection = true;
                // Stop iterating bounding boxes
                throw new IllegalStateException();
            }
        }

        public boolean doContinue() {
            return !foundIntersection;
        }
    }
    private static final class WallIntersectionRayVisitor implements ItemVisitor {
        private static final GeometryFactory FACTORY = new GeometryFactory();
        Set<Integer> itemProcessed = new HashSet<>();
        List<ProfileBuilder.Wall> walls;
        Coordinate p1;
        Coordinate p2;
        LineString seg;
        Set<Integer> wallsInIntersection;
        ProfileBuilder profileBuilder;
        Plane cutPlane;
        List<Coordinate> input;
        boolean foundIntersection = false;

        public WallIntersectionRayVisitor(List<ProfileBuilder.Wall> walls, Coordinate p1,
                                              Coordinate p2, ProfileBuilder profileBuilder, List<Coordinate> input,
                                          Set<Integer> wallsInIntersection, Plane cutPlane) {
            this.profileBuilder = profileBuilder;
            this.input = input;
            this.wallsInIntersection = wallsInIntersection;
            this.cutPlane = cutPlane;
            this.walls = walls;
            this.p1 = p1;
            this.p2 = p2;
            seg = new LineSegment(p1, p2).toGeometry(new GeometryFactory());
        }

        @Override
        public void visitItem(Object item) {
            int id = (Integer) item;
            if(!itemProcessed.contains(id)) {
                itemProcessed.add(id);
                final ProfileBuilder.Wall w = walls.get(id-1);
                RectangleLineIntersector rect = new RectangleLineIntersector(w.getLine().toGeometry(FACTORY).getEnvelopeInternal());
                if (rect.intersects(p1, p2) && w.getLine().toGeometry(FACTORY).intersects(seg)) {
                    addItem(id);
                }
            }
        }

        public void addItem(int id) {
            if (wallsInIntersection.contains(id)) {
                return;
            }
            List<Coordinate> roofPoints = Arrays.asList(profileBuilder.getWall(id-1).getLine().toGeometry(FACTORY).getCoordinates());
            // Create a cut of the building volume
            roofPoints = cutRoofPointsWithPlane(cutPlane, roofPoints);
            if (!roofPoints.isEmpty()) {
                input.addAll(roofPoints);
                wallsInIntersection.add(id);
                foundIntersection = true;
                // Stop iterating bounding boxes
                throw new IllegalStateException();
            }
        }

        public boolean doContinue() {
            return !foundIntersection;
        }
    }

    public static List<Coordinate> cutRoofPointsWithPlane(Plane plane, List<Coordinate> roofPts) {
        List<Coordinate> polyCut = new ArrayList<>(roofPts.size());
        Double lastOffset = null;
        for (int idp = 0; idp < roofPts.size(); idp++) {
            double offset = plane.getOffset(coordinateToVector(roofPts.get(idp)));
            if (lastOffset != null && ((offset >= 0 && lastOffset < 0) || (offset < 0 && lastOffset >= 0))) {
                // Interpolate vector
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(coordinateToVector(roofPts.get(idp - 1)), coordinateToVector(roofPts.get(idp)), epsilon));
                polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            if (offset >= 0) {
                org.apache.commons.math3.geometry.euclidean.threed.Vector3D i = plane.intersection(new Line(new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(roofPts.get(idp).x, roofPts.get(idp).y, Double.MIN_VALUE), coordinateToVector(roofPts.get(idp)), epsilon));
                if(i!=null)polyCut.add(new Coordinate(i.getX(), i.getY(), i.getZ()));
            }
            lastOffset = offset;
        }
        return polyCut;
    }
    public static org.apache.commons.math3.geometry.euclidean.threed.Vector3D coordinateToVector(Coordinate p) {
        return new org.apache.commons.math3.geometry.euclidean.threed.Vector3D(p.x, p.y, p.z);
    }


    private List<MirrorReceiverResult> getMirrorReceivers(List<ProfileBuilder.Wall> buildWalls, Coordinate srcCoord,
                                                         Coordinate rcvCoord, LineSegment srcRcvLine) {
        return getMirrorReceivers(buildWalls, srcCoord, rcvCoord, srcRcvLine, 1, null);
    }

    private List<MirrorReceiverResult> getMirrorReceivers(List<ProfileBuilder.Wall> buildWalls, Coordinate srcCoord,
                                                         Coordinate rcvCoord, LineSegment srcRcvLine, int depth, MirrorReceiverResult parent) {
        List<MirrorReceiverResult> results = new ArrayList<>();
        ProfileBuilder.CutProfile profile = data.profileBuilder.getProfile(srcCoord, rcvCoord);
        for(ProfileBuilder.Wall wall : buildWalls) {
            if(parent != null && buildWalls.indexOf(wall) == parent.getWallId()) {
                continue;
            }
            //Calculate the coordinate of the mirror rcv
            Coordinate proj = wall.getLine().project(rcvCoord);
            Coordinate rcvMirror = new Coordinate(2*proj.x-rcvCoord.x, 2*proj.y-rcvCoord.y, rcvCoord.z);
            //If the mirror rcv is too far, skip it
            if(srcRcvLine.p0.distance(rcvMirror) > data.maxSrcDist) {
                continue;
            }

            LineSegment srcMirrRcvLine = new LineSegment(srcCoord, rcvMirror);
            Coordinate inter = srcMirrRcvLine.intersection(wall.getLine());
            if (inter == null) {
                continue;
            }
            double frac = wall.getLine().segmentFraction(inter);
            inter.z = wall.getLine().p0.z + frac * (wall.getLine().p1.z - wall.getLine().p0.z);
            //Check if an other wall is masking the current
            double dist = new LineSegment(srcCoord, inter).getLength();
            boolean skipWall = false;
            List<ProfileBuilder.Wall> walls = new ArrayList<>();
            buildWalls.forEach(w -> {
                if(w.getOriginId() == wall.getOriginId()) {
                    walls.add(w);
                }
            });
            for (ProfileBuilder.Wall otherWall : walls) {
                Coordinate otherInter = srcMirrRcvLine.intersection(otherWall.getLine());
                if (otherInter != null) {
                    double otherFrac = otherWall.getLine().segmentFraction(otherInter);
                    double otherInterZ = otherWall.getLine().p0.z + otherFrac * (otherWall.getLine().p1.z - otherWall.getLine().p0.z);
                    double d1 = srcMirrRcvLine.segmentFraction(inter);
                    double d2 = srcMirrRcvLine.segmentFraction(otherInter);
                    if (otherInterZ > d2 * inter.z / d1) {
                        double otherDist = new LineSegment(srcCoord, otherInter).getLength();
                        if (otherDist < dist) {
                            skipWall = true;
                            break;
                        }
                    }
                }
            }
            if (!skipWall) {
                if(data.reflexionOrder > depth) {
                    MirrorReceiverResult p = new MirrorReceiverResult(rcvMirror, parent,
                            data.profileBuilder.getProcessedWalls().indexOf(wall), wall.getOriginId());
                    results.addAll(getMirrorReceivers(buildWalls, srcCoord, inter, srcMirrRcvLine, depth+1, p));
                }
                results.add(new MirrorReceiverResult(rcvMirror, parent,
                        data.profileBuilder.getProcessedWalls().indexOf(wall), wall.getOriginId()));
            }
        }
        return results;
    }

    public List<PropagationPath> computeReflexion(Coordinate rcvCoord,
                                                  Coordinate srcCoord, boolean favorable) {

        // Compute receiver mirror
        LineSegment srcRcvLine = new LineSegment(srcCoord, rcvCoord);
        LineIntersector linters = new RobustLineIntersector();
        //Keep only building walls which are not too far.
        List<ProfileBuilder.Wall> buildWalls = data.profileBuilder.getProcessedWalls().stream()
                .filter(wall -> wall.getType().equals(ProfileBuilder.IntersectionType.BUILDING))
                .filter(wall -> wall.getLine().distance(srcRcvLine) < data.maxRefDist)
                .collect(Collectors.toList());

        List<MirrorReceiverResult> mirrorResults = getMirrorReceivers(buildWalls, srcCoord, rcvCoord, srcRcvLine);

        List<PropagationPath> reflexionPropagationPaths = new ArrayList<>();

        for (MirrorReceiverResult receiverReflection : mirrorResults) {
            ProfileBuilder.Wall seg = data.profileBuilder.getProcessedWalls().get(receiverReflection.getWallId());
            List<MirrorReceiverResult> rayPath = new ArrayList<>(data.reflexionOrder + 2);
            boolean validReflection = false;
            MirrorReceiverResult receiverReflectionCursor = receiverReflection;
            // Test whether intersection point is on the wall
            // segment or not
            Coordinate destinationPt = new Coordinate(srcCoord);

            linters.computeIntersection(seg.getLine().p0, seg.getLine().p1,
                    receiverReflection.getReceiverPos(),
                    destinationPt);
            while (linters.hasIntersection() /*&& MirrorReceiverIterator.wallPointTest(seg.getLine(), destinationPt)*/) {
                // There are a probable reflection point on the segment
                Coordinate reflectionPt = new Coordinate(
                        linters.getIntersection(0));
                if (reflectionPt.equals(destinationPt)) {
                    break;
                }
                Coordinate vec_epsilon = new Coordinate(
                        reflectionPt.x - destinationPt.x,
                        reflectionPt.y - destinationPt.y);
                double length = vec_epsilon
                        .distance(new Coordinate(0., 0., 0.));
                // Normalize vector
                vec_epsilon.x /= length;
                vec_epsilon.y /= length;
                // Multiply by epsilon in meter
                vec_epsilon.x *= wideAngleTranslationEpsilon;
                vec_epsilon.y *= wideAngleTranslationEpsilon;
                // Translate reflection pt by epsilon to get outside
                // the wall
                reflectionPt.x -= vec_epsilon.x;
                reflectionPt.y -= vec_epsilon.y;
                // Compute Z interpolation
                reflectionPt.setOrdinate(Coordinate.Z, Vertex.interpolateZ(linters.getIntersection(0),
                        receiverReflectionCursor.getReceiverPos(), destinationPt));

                // Test if there is no obstacles between the
                // reflection point and old reflection pt (or source position)
                validReflection = Double.isNaN(receiverReflectionCursor.getReceiverPos().z) ||
                        Double.isNaN(reflectionPt.z) || Double.isNaN(destinationPt.z) /*|| seg.getOriginId() == 0*/
                        || (reflectionPt.z < data.profileBuilder.getBuilding(seg.getOriginId()).getGeometry().getCoordinate().z
                        && reflectionPt.z > data.profileBuilder.getZGround(reflectionPt)
                        && destinationPt.z > data.profileBuilder.getZGround(destinationPt));
                if (validReflection) // Source point can see receiver image
                {
                    MirrorReceiverResult reflResult = new MirrorReceiverResult(receiverReflectionCursor);
                    reflResult.setReceiverPos(reflectionPt);
                    rayPath.add(reflResult);
                    if (receiverReflectionCursor
                            .getParentMirror() == null) { // Direct to the receiver
                        break; // That was the last reflection
                    } else {
                        // There is another reflection
                        destinationPt.setCoordinate(reflectionPt);
                        // Move reflection information cursor to a
                        // reflection closer
                        receiverReflectionCursor = receiverReflectionCursor.getParentMirror();
                        // Update intersection data
                        seg = buildWalls
                                .get(receiverReflectionCursor
                                        .getWallId());
                        linters.computeIntersection(seg.getLine().p0, seg.getLine().p1,
                                receiverReflectionCursor
                                        .getReceiverPos(),
                                destinationPt
                        );
                        validReflection = false;
                    }
                } else {
                    break;
                }
            }
            if (validReflection && !rayPath.isEmpty()) {
                // Check intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiverResult refl = rayPath.get(idPt + 1);
                    ProfileBuilder.CutProfile profile = data.profileBuilder.getProfile(firstPt, refl.getReceiverPos(), data.gS);
                    if (profile.intersectTopography() || profile.intersectBuilding() ) {
                        validReflection = false;
                        break;
                    }
                }
                if (!validReflection) {
                    continue;
                }
                // A valid propagation path as been found
                List<PointPath> points = new ArrayList<PointPath>();
                List<SegmentPath> segments = new ArrayList<SegmentPath>();
                SegmentPath srPath = null;
                // Compute direct path between source and first reflection point, add profile to the data
                computeReflexionOverBuildings(srcCoord, rayPath.get(0).getReceiverPos(), points, segments, srPath, data);
                if (points.isEmpty()) {
                    continue;
                }
                PointPath reflPoint = points.get(points.size() - 1);
                reflPoint.setType(PointPath.POINT_TYPE.REFL);
                reflPoint.setBuildingId(rayPath.get(0).getBuildingId());
                reflPoint.setAlphaWall(data.profileBuilder.getBuilding(reflPoint.getBuildingId()).getAlphas());
                // Add intermediate reflections
                for (int idPt = 0; idPt < rayPath.size() - 1; idPt++) {
                    Coordinate firstPt = rayPath.get(idPt).getReceiverPos();
                    MirrorReceiverResult refl = rayPath.get(idPt + 1);
                    reflPoint = new PointPath(refl.getReceiverPos(), 0, 1, data.profileBuilder.getBuilding(refl.getBuildingId()).getAlphas(), PointPath.POINT_TYPE.REFL);
                    reflPoint.setBuildingId(refl.getBuildingId());
                    points.add(reflPoint);
                    segments.add(new SegmentPath(1, new Vector3D(firstPt), refl.getReceiverPos()));
                }
                // Compute direct path between receiver and last reflection point, add profile to the data
                List<PointPath> lastPts = new ArrayList<>();
                computeReflexionOverBuildings(rayPath.get(rayPath.size() - 1).getReceiverPos(), rcvCoord, lastPts, segments, srPath, data);
                if (lastPts.isEmpty()) {
                    continue;
                }
                points.addAll(lastPts.subList(1, lastPts.size()));
                for (int i = 1; i < points.size(); i++) {
                    if (points.get(i).type == PointPath.POINT_TYPE.REFL) {
                        if (i < points.size() - 1) {
                            // A diffraction point may have offset in height the reflection coordinate
                            points.get(i).coordinate.z = Vertex.interpolateZ(points.get(i).coordinate, points.get(i - 1).coordinate, points.get(i + 1).coordinate);
                            //check if in building && if under floor
                            if (points.get(i).coordinate.z > data.profileBuilder.getBuilding(points.get(i).getBuildingId()).getGeometry().getCoordinate().z
                                    || points.get(i).coordinate.z <= data.profileBuilder.getZGround(points.get(i).coordinate)) {
                                points.clear();
                                segments.clear();
                                break;
                            }
                        } else {
                            LOGGER.warn("Invalid state, reflexion point on last point");
                            points.clear();
                            segments.clear();
                            break;
                        }
                    }
                }
                if (points.size() > 2) {
                    reflexionPropagationPaths.add(new PropagationPath(favorable, points, segments, srPath));
                }
            }
        }
        return reflexionPropagationPaths;
    }


    public void computeReflexionOverBuildings(Coordinate p0, Coordinate p1, List<PointPath> points, List<SegmentPath> segments, SegmentPath srPath, CnossosPropagationData data) {
        List<PropagationPath> propagationPaths = directPath(p0, -1, null, p1, -1);
        if (!propagationPaths.isEmpty()) {
            PropagationPath propagationPath = propagationPaths.get(0);
            points.addAll(propagationPath.getPointList());
            segments.addAll(propagationPath.getSegmentList());
            //srPath.add(new SegmentPath(1.0, new Vector3D(p0, p1), p0));
        }
    }
    /**
     * @param geom                  Geometry
     * @param segmentSizeConstraint Maximal distance between points
     * @return Fixed distance between points
     * @param[out] pts computed points
     */
    public static double splitLineStringIntoPoints(LineString geom, double segmentSizeConstraint,
                                                   List<Coordinate> pts) {
        // If the linear sound source length is inferior than half the distance between the nearest point of the sound
        // source and the receiver then it can be modelled as a single point source
        double geomLength = geom.getLength();
        if (geomLength < segmentSizeConstraint) {
            // Return mid point
            Coordinate[] points = geom.getCoordinates();
            double segmentLength = 0;
            final double targetSegmentSize = geomLength / 2.0;
            for (int i = 0; i < points.length - 1; i++) {
                Coordinate a = points[i];
                final Coordinate b = points[i + 1];
                double length = a.distance3D(b);
                if (length + segmentLength > targetSegmentSize) {
                    double segmentLengthFraction = (targetSegmentSize - segmentLength) / length;
                    Coordinate midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                            a.y + segmentLengthFraction * (b.y - a.y),
                            a.z + segmentLengthFraction * (b.z - a.z));
                    pts.add(midPoint);
                    break;
                }
                segmentLength += length;
            }
            return geom.getLength();
        } else {
            double targetSegmentSize = geomLength / Math.ceil(geomLength / segmentSizeConstraint);
            Coordinate[] points = geom.getCoordinates();
            double segmentLength = 0.;

            // Mid point of segmented line source
            Coordinate midPoint = null;
            for (int i = 0; i < points.length - 1; i++) {
                Coordinate a = points[i];
                final Coordinate b = points[i + 1];
                double length = a.distance3D(b);
                if (Double.isNaN(length)) {
                    length = a.distance(b);
                }
                while (length + segmentLength > targetSegmentSize) {
                    //LineSegment segment = new LineSegment(a, b);
                    double segmentLengthFraction = (targetSegmentSize - segmentLength) / length;
                    Coordinate splitPoint = new Coordinate();
                    splitPoint.x = a.x + segmentLengthFraction * (b.x - a.x);
                    splitPoint.y = a.y + segmentLengthFraction * (b.y - a.y);
                    splitPoint.z = a.z + segmentLengthFraction * (b.z - a.z);
                    if (midPoint == null && length + segmentLength > targetSegmentSize / 2) {
                        segmentLengthFraction = (targetSegmentSize / 2.0 - segmentLength) / length;
                        midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                                a.y + segmentLengthFraction * (b.y - a.y),
                                a.z + segmentLengthFraction * (b.z - a.z));
                    }
                    pts.add(midPoint);
                    a = splitPoint;
                    length = a.distance3D(b);
                    if (Double.isNaN(length)) {
                        length = a.distance(b);
                    }
                    segmentLength = 0;
                    midPoint = null;
                }
                if (midPoint == null && length + segmentLength > targetSegmentSize / 2) {
                    double segmentLengthFraction = (targetSegmentSize / 2.0 - segmentLength) / length;
                    midPoint = new Coordinate(a.x + segmentLengthFraction * (b.x - a.x),
                            a.y + segmentLengthFraction * (b.y - a.y),
                            a.z + segmentLengthFraction * (b.z - a.z));
                }
                segmentLength += length;
            }
            if (midPoint != null) {
                pts.add(midPoint);
            }
            return targetSegmentSize;
        }
    }


    /**
     * Update ground Z coordinates of sound sources absolute to sea levels
     */
    public void makeSourceRelativeZToAbsolute() {
        AbsoluteCoordinateSequenceFilter filter = new AbsoluteCoordinateSequenceFilter(data.profileBuilder, true);
        List<Geometry> sourceCopy = new ArrayList<>(data.sourceGeometries.size());
        for (Geometry source : data.sourceGeometries) {
            filter.reset();
            Geometry cpy = source.copy();
            cpy.apply(filter);
            sourceCopy.add(cpy);
        }
        data.sourceGeometries = sourceCopy;
    }

    /**
     * Update ground Z coordinates of sound sources and receivers absolute to sea levels
     */
    public void makeRelativeZToAbsolute() {
        makeSourceRelativeZToAbsolute();
        makeReceiverRelativeZToAbsolute();
    }

    /**
     * Update ground Z coordinates of receivers absolute to sea levels
     */
    public void makeReceiverRelativeZToAbsolute() {
        AbsoluteCoordinateSequenceFilter filter = new AbsoluteCoordinateSequenceFilter(data.profileBuilder, true);
        CoordinateSequence sequence = new CoordinateArraySequence(data.receivers.toArray(new Coordinate[data.receivers.size()]));
        for (int i = 0; i < sequence.size(); i++) {
            filter.filter(sequence, i);
        }
        data.receivers = Arrays.asList(sequence.toCoordinateArray());
    }

    private static double insertPtSource(Coordinate source, Coordinate receiverPos, Integer sourceId,
                                         List<SourcePointInfo> sourceList, double[] wj, double li, Orientation orientation) {
        // Compute maximal power at freefield at the receiver position with reflective ground
        double aDiv = -getADiv(CGAlgorithms3D.distance(receiverPos, source));
        double[] srcWJ = new double[wj.length];
        for (int idFreq = 0; idFreq < srcWJ.length; idFreq++) {
            srcWJ[idFreq] = wj[idFreq] * li * dbaToW(aDiv) * dbaToW(3);
        }
        sourceList.add(new SourcePointInfo(srcWJ, sourceId, source, li, orientation));
        return sumArray(srcWJ.length, srcWJ);
    }

    private static double insertPtSource(Point source, Coordinate receiverPos, Integer sourceId,
                                         List<SourcePointInfo> sourceList, double[] wj, double li, Orientation orientation) {
        // Compute maximal power at freefield at the receiver position with reflective ground
        double aDiv = -getADiv(CGAlgorithms3D.distance(receiverPos, source.getCoordinate()));
        double[] srcWJ = new double[wj.length];
        for (int idFreq = 0; idFreq < srcWJ.length; idFreq++) {
            srcWJ[idFreq] = wj[idFreq] * li * dbaToW(aDiv) * dbaToW(3);
        }
        sourceList.add(new SourcePointInfo(srcWJ, sourceId, source.getCoordinate(), li, orientation));
        return sumArray(srcWJ.length, srcWJ);
    }

    private double addLineSource(LineString source, Coordinate receiverCoord, int srcIndex, List<SourcePointInfo> sourceList, double[] wj) {
        double totalPowerRemaining = 0;
        ArrayList<Coordinate> pts = new ArrayList<>();
        // Compute li to equation 4.1 NMPB 2008 (June 2009)
        Coordinate nearestPoint = JTSUtility.getNearestPoint(receiverCoord, source);
        double segmentSizeConstraint = max(1, receiverCoord.distance3D(nearestPoint) / 2.0);
        if (Double.isNaN(segmentSizeConstraint)) {
            segmentSizeConstraint = max(1, receiverCoord.distance(nearestPoint) / 2.0);
        }
        double li = splitLineStringIntoPoints(source, segmentSizeConstraint, pts);
        for (int ptIndex = 0; ptIndex < pts.size(); ptIndex++) {
            Coordinate pt = pts.get(ptIndex);
            if (pt.distance(receiverCoord) < data.maxSrcDist) {
                // use the orientation computed from the line source coordinates
                Vector3D v;
                if(ptIndex == 0) {
                    v = new Vector3D(source.getCoordinates()[0], pts.get(ptIndex));
                } else {
                    v = new Vector3D(pts.get(ptIndex - 1), pts.get(ptIndex));
                }
                Orientation orientation;
                if(data.sourcesPk.size() > srcIndex && data.sourceOrientation.containsKey(data.sourcesPk.get(srcIndex))) {
                    // If the line source already provide an orientation then alter the line orientation
                    orientation = data.sourceOrientation.get(data.sourcesPk.get(srcIndex));
                    orientation = Orientation.fromVector(
                            Orientation.rotate(new Orientation(orientation.yaw, orientation.roll, 0),
                                    v.normalize()), orientation.roll);
                } else {
                    orientation = Orientation.fromVector(v.normalize(), 0);
                }
                totalPowerRemaining += insertPtSource(pt, receiverCoord, srcIndex, sourceList, wj, li, orientation);
            }
        }
        return totalPowerRemaining;
    }

    private static final class RangeReceiversComputation implements Runnable {
        private final int startReceiver; // Included
        private final int endReceiver; // Excluded
        private final ComputeCnossosRays propagationProcess;
        private final ProgressVisitor visitor;
        private final IComputeRaysOut dataOut;
        private final CnossosPropagationData data;

        public RangeReceiversComputation(int startReceiver, int endReceiver, ComputeCnossosRays propagationProcess,
                                         ProgressVisitor visitor, IComputeRaysOut dataOut,
                                         CnossosPropagationData data) {
            this.startReceiver = startReceiver;
            this.endReceiver = endReceiver;
            this.propagationProcess = propagationProcess;
            this.visitor = visitor;
            this.dataOut = dataOut.subProcess();
            this.data = data;
        }

        @Override
        public void run() {
            try {
                for (int idReceiver = startReceiver; idReceiver < endReceiver; idReceiver++) {
                    if (visitor != null) {
                        if (visitor.isCanceled()) {
                            break;
                        }
                    }
                    ReceiverPointInfo rcv = new ReceiverPointInfo(idReceiver, data.receivers.get(idReceiver));

                    propagationProcess.computeRaysAtPosition(rcv, dataOut, visitor);

                    if (visitor != null) {
                        visitor.endStep();
                    }
                }
            } catch (Exception ex) {
                LOGGER.error(ex.getLocalizedMessage(), ex);
                if (visitor != null) {
                    visitor.cancel();
                }
                throw ex;
            }
        }
    }


    private static final class ReceiverPointInfo {
        private int sourcePrimaryKey;
        private Coordinate position;

        public ReceiverPointInfo(int sourcePrimaryKey, Coordinate position) {
            this.sourcePrimaryKey = sourcePrimaryKey;
            this.position = position;
        }

        public Coordinate getCoord() {
            return position;
        }

        public int getId() {
            return sourcePrimaryKey;
        }
    }

    private static final class SourcePointInfo implements Comparable<SourcePointInfo> {
        private final double li;
        private final int sourcePrimaryKey;
        private Coordinate position;
        private final double globalWj;
        private Orientation orientation;

        /**
         * @param wj               Maximum received power from this source
         * @param sourcePrimaryKey
         * @param position
         */
        public SourcePointInfo(double[] wj, int sourcePrimaryKey, Coordinate position, double li, Orientation orientation) {
            this.sourcePrimaryKey = sourcePrimaryKey;
            this.position = position;
            if (Double.isNaN(position.z)) {
                this.position = new Coordinate(position.x, position.y, 0);
            }
            this.globalWj = sumArray(wj.length, wj);
            this.li = li;
            this.orientation = orientation;
        }

        public Orientation getOrientation() {
            return orientation;
        }

        public Coordinate getCoord() {
            return position;
        }

        public int getId() {
            return sourcePrimaryKey;
        }

        @Override
        public int compareTo(SourcePointInfo sourcePointInfo) {
            int cmp = -Double.compare(globalWj, sourcePointInfo.globalWj);
            if (cmp == 0) {
                return Integer.compare(sourcePrimaryKey, sourcePointInfo.sourcePrimaryKey);
            } else {
                return cmp;
            }
        }
    }

    enum ComputationSide {LEFT, RIGHT}


    public static final class AbsoluteCoordinateSequenceFilter implements CoordinateSequenceFilter {
        AtomicBoolean geometryChanged = new AtomicBoolean(false);
        ProfileBuilder profileBuilder;
        boolean resetZ;

        /**
         * Constructor
         *
         * @param profileBuilder Initialised instance of profileBuilder
         * @param resetZ              If filtered geometry contain Z and resetZ is false, do not update Z.
         */
        public AbsoluteCoordinateSequenceFilter(ProfileBuilder profileBuilder, boolean resetZ) {
            this.profileBuilder = profileBuilder;
            this.resetZ = resetZ;
        }

        public void reset() {
            geometryChanged.set(false);
        }

        @Override
        public void filter(CoordinateSequence coordinateSequence, int i) {
            Coordinate pt = coordinateSequence.getCoordinate(i);
            Double zGround = profileBuilder.getZGround(pt);
            if (!zGround.isNaN() && (resetZ || Double.isNaN(pt.getOrdinate(2)) || Double.compare(0, pt.getOrdinate(2)) == 0)) {
                pt.setOrdinate(2, zGround + (Double.isNaN(pt.getOrdinate(2)) ? 0 : pt.getOrdinate(2)));
                geometryChanged.set(true);
            }
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public boolean isGeometryChanged() {
            return geometryChanged.get();
        }
    }
}