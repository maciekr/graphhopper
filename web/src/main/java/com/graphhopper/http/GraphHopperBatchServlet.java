package com.graphhopper.http;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PointPair;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPlace;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.inject.Inject;
import javax.inject.Named;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static javax.servlet.http.HttpServletResponse.SC_INTERNAL_SERVER_ERROR;

public class GraphHopperBatchServlet extends GHServlet {
    @Inject
    private GraphHopper hopper;
    @Inject
    @Named("defaultAlgorithm")
    private String defaultAlgorithm;
    @Inject
    @Named("timeout")
    private Long timeOutInMillis;

    //this is an educated guess from a few tests ran
    //basically, seems like it's not 100% CPU bound so a little extra on top of core count seems to help with latency and overall throughput
    //tested for batches of 24 (the actual batches may be even bigger)
    //UPDATE: actually not using it for the time being
    private static final ExecutorService threadPool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);

    @Override
    public void doPost(HttpServletRequest req, HttpServletResponse res) throws ServletException, IOException {
        try {
            writePath(req, res);
        } catch (Exception ex) {
            logger.error("Error while executing request: " + req.getQueryString(), ex);
            writeError(res, SC_INTERNAL_SERVER_ERROR, "Problem occured:" + ex.getMessage());
        }
    }

    void writePath(HttpServletRequest req, HttpServletResponse res) throws Exception {
        StopWatch sw = new StopWatch().start();
        try {
            List<PointPair> pointPairs = getPointPairs(req);
            // we can reduce the path length based on the maximum differences to the original coordinates
            final double minPathPrecision = getDoubleParam(req, "minPathPrecision", 1d);
            final boolean enableInstructions = getBooleanParam(req, "instructions", true);
            final boolean calcPoints = getBooleanParam(req, "calcPoints", true);

            final String vehicleStr = getParam(req, "vehicle", "CAR").toUpperCase();
            String weightingParam = getParam(req, "weighting", "fastest");
            // REMOVE_IN 0.3
            if (req.getParameterMap().containsKey("algoType"))
                weightingParam = getParam(req, "algoType", "fastest");
            final String weighting = weightingParam;
            final String algoStr = getParam(req, "algorithm", defaultAlgorithm);

            sw = new StopWatch().start();

            final Queue<GHResponse> responses = new ConcurrentLinkedQueue<GHResponse>();
            final List<Callable<Object>> tasks = new ArrayList<Callable<Object>>();
            if (hopper.getEncodingManager().supports(vehicleStr)) {
                final FlagEncoder algoVehicle = hopper.getEncodingManager().getEncoder(vehicleStr);
                for (final PointPair points : pointPairs) {
//                    tasks.add(new Callable<Object>() {
//                        @Override
//                        public Object call() throws Exception {
                            GHPlace start = points.getFrom();
                            GHPlace end = points.getTo();

                            GHResponse ghResponse = hopper.route(new GHRequest(start, end).
                                    setVehicle(algoVehicle.toString()).
                                    setWeighting(weighting).
                                    setAlgorithm(algoStr).
                                    putHint("calcPoints", calcPoints).
                                    putHint("instructions", enableInstructions).
                                    putHint("douglas.minprecision", minPathPrecision));
                            ghResponse.setPointPair(points);

                            responses.add(ghResponse);
//                            return null;
//                        }
//                    });
                }
            } else {
                throw new IllegalArgumentException("Vehicle not supported: " + vehicleStr);
            }

            threadPool.invokeAll(tasks);

            float took = sw.stop().getSeconds();
            String infoStr = req.getRemoteAddr() + " " + req.getLocale() + " " + req.getHeader("User-Agent");

            JSONObject result = new JSONObject();
            if (!responses.isEmpty()) {
                GHResponse ghResponse = null;
                while ((ghResponse = responses.poll()) != null) {
                    double distInMeter = ghResponse.getDistance();
                    PointPair pointPair = ghResponse.getPointPair();
                    JSONObject jsonObject = new JSONObject();
                    jsonObject.put("hasErrors", ghResponse.hasErrors())
                            .put("status", ghResponse.isFound())
                            .put("from", pointPair.getFrom().toGeoJson()) //geojson so lon,lat!! and not lat,lon...
                            .put("to", pointPair.getTo().toGeoJson());

                    if (ghResponse.hasErrors()) {
                        List<Map<String, String>> list = new ArrayList<Map<String, String>>();
                        for (Throwable t : ghResponse.getErrors()) {
                            Map<String, String> map = new HashMap<String, String>();
                            map.put("errorMessage", t.getMessage());
                            map.put("details", t.getClass().getName());
                            list.add(map);
                        }
                        jsonObject.put("errors", list);
                    } else {
                        jsonObject
                                .put("distance", distInMeter) //In meters
                                .put("time", ghResponse.getTime()); //In seconds
                    }
                    result.put(String.valueOf(pointPair.getId()), jsonObject);

                    if (ghResponse.hasErrors())
                        logger.error(pointPair.getFrom() + "," + pointPair.getTo() + " errors:" + ghResponse.getErrors());
                    else
                        logger.info(infoStr + " " + pointPair.getFrom() + "->" + pointPair.getTo()
                                + ", distance: " + distInMeter + ", time:" + Math.round(ghResponse.getTime() / 60f)
                                + "min, from:" + pointPair.getFrom() + ", to:" + pointPair.getTo()
                                + ", debug - " + ghResponse.getDebugInfo() + ", " + algoStr + ", "
                                + weighting + ", " + vehicleStr);
                }
                writeJson(req, res, result);
            }

            logger.info(String.format("Routing request:\nFrom:%s\nTo:%s\nDONE in time: %s", Arrays.toString(getParams(req, "from")),
                    Arrays.toString(getParams(req, "to")), took));

        } catch (Exception ex) {
            logger.error("Error while query " + req.getParameterMap() + " : " + ex, ex);
            writeError(res, SC_INTERNAL_SERVER_ERROR, "Problem occured:" + ex.getMessage());
        }
    }

    private List<PointPair> getPointPairs(HttpServletRequest req) throws IOException {
        String[] froms = getParams(req, "from");
        String[] tos = getParams(req, "to");

        final List<PointPair> pointPairs = new ArrayList<PointPair>();
        for (int i = 0; i < froms.length; i++) {
            GHPlace from = getPoint(froms[i]);
            GHPlace to = getPoint(tos[i]);
            pointPairs.add(new PointPair(i, from, to));
        }

        return pointPairs;
    }

    private GHPlace getPoint(String latlonStr) {
        String[] latlon = latlonStr.split(",");
        double lat = Double.valueOf(latlon[0]);
        double lon = Double.valueOf(latlon[1]);
        return new GHPlace(lat, lon);
    }
}
