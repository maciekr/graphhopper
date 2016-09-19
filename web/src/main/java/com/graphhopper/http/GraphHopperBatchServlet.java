package com.graphhopper.http;

import com.graphhopper.GHRequest;
import com.graphhopper.GHResponse;
import com.graphhopper.GraphHopper;
import com.graphhopper.PathWrapper;
import com.graphhopper.routing.util.FlagEncoder;
import com.graphhopper.routing.util.HintsMap;
import com.graphhopper.util.StopWatch;
import com.graphhopper.util.shapes.GHPoint;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.util.*;
import java.util.function.BiFunction;

import static javax.servlet.http.HttpServletResponse.SC_BAD_REQUEST;

public class GraphHopperBatchServlet extends GHBaseServlet {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    @Inject
    private GraphHopper hopper;
    @Inject
    private RouteSerializer routeSerializer;

    @Override
    public void doGet(HttpServletRequest httpReq, HttpServletResponse httpRes ) throws ServletException, IOException
    {

        GHPoint origin = getPoints(httpReq, "origin").get(0);
        List<GHPoint> destinations = getPoints(httpReq, "d");

        double minPathPrecision = getDoubleParam(httpReq, "way_point_max_distance", 1d);
        String vehicleStr = getParam(httpReq, "vehicle", "car");
        String weighting = getParam(httpReq, "weighting", "fastest");
        String algoStr = getParam(httpReq, "algorithm", "");
        String localeStr = getParam(httpReq, "locale", "en");

        StopWatch sw = new StopWatch().start();
//        List<Throwable> errorList = new ArrayList<Throwable>();
//        ghRsp.addErrors(errorList);

//        if (errorList.isEmpty())
        List<Map<String, Object>> distances = new ArrayList<>();
        {
            FlagEncoder algoVehicle = hopper.getEncodingManager().getEncoder(vehicleStr);
            destinations.forEach((destination) -> {
                GHRequest request = new GHRequest(origin, destination);

//                initHints(request, httpReq.getParameterMap());
                request.setVehicle(algoVehicle.toString()).
                        setWeighting(weighting).
                        setAlgorithm(algoStr).
                        setLocale(localeStr).
                        getHints().
                        put("calcPoints", false).
                        put("instructions", false).
                        put("wayPointMaxDistance", minPathPrecision);

                GHResponse ghRsp = hopper.route(request);
                if (!ghRsp.getAll().isEmpty() && !ghRsp.hasErrors()) {
                    distances.add(new HashMap<String, Object>(){{
                        put("location", destination.toString());
                        put("duration", new HashMap<String, Object>(){{
                            put("value", ghRsp.getBest().getTime() / 1000);
                        }});
                        put("status", "OK");
                    }});
                } else {
                    distances.add(new HashMap<String, Object>(){{
                        put("location", destination.toString());
                        put("status", "ZERO_RESULTS");
                    }});
                }
            });
        }

        float took = sw.stop().getSeconds();
        Map<String, Object> res = new HashMap<>();
        res.put("took", Math.round(took * 1000));
        res.put("rows", new ArrayList<Object>(){{
            add(new HashMap<String, Object>(){{
                put("elements", distances);
            }});
        }});

        String infoStr = httpReq.getRemoteAddr() + " " + httpReq.getLocale() + " " + httpReq.getHeader("User-Agent");
        logger.info(httpReq.getQueryString() + " " + infoStr + ", origin: " + origin + ", destinations: " + destinations + ", took:"
                + took + ", " + algoStr + ", " + weighting + ", " + vehicleStr);
        httpRes.setHeader("X-GH-Took", "" + Math.round(took * 1000));

        writeJson(httpReq, httpRes, new JSONObject(res));
    }

    protected List<GHPoint> getPoints( HttpServletRequest req, String key )
    {
        String[] pointsAsStr = getParams(req, key);
        final List<GHPoint> infoPoints = new ArrayList<GHPoint>(pointsAsStr.length);
        for (String str : pointsAsStr)
        {
            String[] fromStrs = str.split(",");
            if (fromStrs.length == 2)
            {
                GHPoint point = GHPoint.parse(str);
                if (point != null)
                    infoPoints.add(point);
            }
        }

        return infoPoints;
    }

    protected void initHints( GHRequest request, Map<String, String[]> parameterMap )
    {
        HintsMap m = request.getHints();
        for (Map.Entry<String, String[]> e : parameterMap.entrySet())
        {
            if (e.getValue().length == 1)
                m.put(e.getKey(), e.getValue()[0]);
        }
    }


}
