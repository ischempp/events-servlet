package org.fhcrc.centernet.servlet;

import java.io.PrintWriter;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import javax.jcr.Session;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.sling.SlingServlet;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.search.PredicateGroup;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.Hit;
import com.day.cq.tagging.TagManager;
import com.day.cq.wcm.api.Page;

import org.fhcrc.centernet.Constants;
import org.fhcrc.centernet.helper.EventHelper;

@SlingServlet(label="Fred Hutch Event Listing Servlet",
	name="org.fhcrc.centernet.servlet.EventsServlet",
	methods={"POST","GET"},
	metatype=true)
@Properties({
	@Property(label = "Event Lister Servlet URL", 
			name="sling.servlet.paths",
			value="/bin/fhcrc/centernet/events",
			propertyPrivate = true)
})
public class EventsServlet extends SlingAllMethodsServlet {
	
	private static final long serialVersionUID = -71412407L;
	private static final Logger log = LoggerFactory.getLogger(EventsServlet.class);
	
	/* Declare constants */
	private final String PN_QUERY_START_TIME = "@jcr:content/eventdetails/start";
	private final String PN_TAG_PROPERTY = "jcr:content/cq:tags";
	private final String PN_MAX_NUMBER = "maxNum";
	private final String PN_TAGS = "tags";
	private final String DEBUG_PREFIX = "EVENTS SERVLET: ";
	
	@Reference
	ResourceResolverFactory factory;
	
	/* Class variables */
	private ResourceResolver resolver;
	private QueryBuilder qb;
	private Session session;
	private PrintWriter writer;
	private TagManager tagManager;
	
	private List<Hit> hitList;
	private HashMap<String, String> queryMap;
	private String maxNum = "-1";
	private String tags = null;
	
	/*
	 * (non-Javadoc)
	 * @see org.apache.sling.api.servlets.SlingSafeMethodsServlet#doGet(org.apache.sling.api.SlingHttpServletRequest, org.apache.sling.api.SlingHttpServletResponse)
	 * 
	 * The GET request could provide instructions on how to use the actual POST version of the servlet
	 */
	protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) {
		
		try {

			writer = response.getWriter();
			response.setContentType("text/html;charset=UTF-8");
			
			writer.println("<!DOCTYPE HTML>");
			writer.println("<html>");
			writer.println("<head><title>Fred Hutch Event Listing Servlet</title></head>");
			writer.println("<body>");
			writer.println("<h1>Fred Hutch Event Listing Servlet</h1>");
			writer.println("<p>The following variables can be passed to this servlet in a POST request:</p>");
			writer.println("<ul>");
			writer.println("<li><b>maxNum</b> - An integer representing the maximum number of results you want back.</li>");
			writer.println("<li><b>tags</b> - A comma-separated list of tagIDs. Only events that have at least one of the tags will be returned.</li>");
			writer.println("</ul>");
			writer.println("<p>If you are having problems with this servlet, please contact <a href=\"mailto:webadm@fredhutch.org\">Communications and Marketing</a>.</p>");
			writer.println("</body>");
			writer.println("</html>");
			
		} catch (Exception e) {
			log.error(DEBUG_PREFIX + "Could not process GET request", e);
		}
		
	}
	
	protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) {
		
		try {
			resolver = factory.getAdministrativeResourceResolver(null);
			qb = resolver.adaptTo(QueryBuilder.class);
			session = resolver.adaptTo(Session.class);
			tagManager = resolver.adaptTo(TagManager.class);
			writer = response.getWriter();
			
			if (request.getParameter(PN_MAX_NUMBER) != null) {

				maxNum = request.getParameter(PN_MAX_NUMBER);
				/* Check to see if the maxNum parameter is actually an integer */
				try {
					
					Integer.parseInt(maxNum);
					
				} catch (NumberFormatException e) {
					
					log.warn(DEBUG_PREFIX + "Non-integer string sent as maxNum: " + maxNum);
					maxNum = "-1";
					
				}
				
			}
			
			if (request.getParameter(PN_TAGS) != null) {
				tags = request.getParameter(PN_TAGS);
			}
			
			queryMap = createQueryMap();
			hitList = qb.createQuery(PredicateGroup.create(queryMap), session).getResult().getHits();
			JSONObject json = getMasterJson(hitList);
			
			json.write(writer);
			
//			writer.println(json.toString());
//			writer.println("HI THERE POST!");
		} catch (Exception e) {
			log.error(e.getMessage());
		}
		
	}
	
	/*
	 * Creates the Map that will be used to execute the Query over the JCR.
	 */
	private HashMap<String,String> createQueryMap() {
		
		HashMap<String, String> map = new HashMap<String, String>();
		
		// Only include Pages with the CenterNet Event template
		map.put("type","cq:Page");
		map.put("path", Constants.EVENTS);
		map.put("property","jcr:content/cq:template");
	    map.put("property.value", Constants.EVENT_TEMPLATE);

	    if (tags != null) {
	    	map = addTagFilter(map);
	    }

	    // Only include Events whose start time is in the future
	    map.put("relativedaterange.property", PN_QUERY_START_TIME);
	    map.put("relativedaterange.lowerBound", "-0d");
	    
	    // Include all hits
	    //map.put("p.limit", maxNum.toString());
	    map.put("p.limit", maxNum);
	    map.put("p.guessTotal", "true");
	    
	    // Order by Start Time
	    map.put("orderby", PN_QUERY_START_TIME);
	    map.put("orderby.sort", "asc");
	    
		return map;
		
	}
	
	private JSONObject getMasterJson(List<Hit> hitlist) {
		
		JSONObject json = new JSONObject();
		JSONObject pageJson = new JSONObject();
		Iterator<Hit> hitIterator = hitlist.iterator();
		
		while (hitIterator.hasNext()) {
			
			try {
				
				Page p = hitIterator.next().getResource().adaptTo(Page.class);
				pageJson = getPageJson(p);
				json.put(p.getPath(), pageJson);
				pageJson = null;
				
			} catch (Exception e) {
				
				log.error(DEBUG_PREFIX + "Problem adapting hit to page", e);
			}
			
		}
		
		return json;
		
	}
	
	/*
	 * Returns a list that can be iterated over via data-sly-list. Contains the
	 * following fields:
	 * 
	 * title - The [Navigation] Title of the page
	 * summary - A brief summary of the event
	 * speaker - The name/title of the person speaking at the event
	 * location - The room in which the event will be taking place
	 * locationMap - URL of a map to the location, in PDF form
	 * host - Department/Division that is hosting the event
	 * contactName - Name of the person to be contacted with questions
	 * contactEmail - Email address of contactName
	 * contactPhone - Phone number of contactName
	 * unixtime - Start time of the event in milliseconds since Unix epoch
	 * external - "true" if speaker is NOT from Fred Hutch, "false" otherwise
	 * dayOfWeek - "Today" if the event's Start Date is today, otherwise it is
	 * 	the full name of that day ("Monday", "Tuesday", etc)
	 * eventDate - Month and day-of-month of the event's Start Date
	 * startTime - hour:minute of the event's Start Date ("noon" if 12:00pm)
	 * endTime - hour:minute of the event's End Date ("noon" if 12:00pm)
	 */
	private JSONObject getPageJson(Page p) {
		
		JSONObject json = new JSONObject();
		Resource eventdetail = p.getContentResource().getChild("eventdetails");
		Boolean hasDetails = eventdetail != null;
		
		try {
			
			json.put("title", p.getNavigationTitle() != null ? p.getNavigationTitle() : p.getTitle());
			json.put("summary", p.getDescription());
			
			if (hasDetails) {
				
				ValueMap properties = eventdetail.getValueMap();
				Calendar startCal = eventdetail.getValueMap().get("start", Calendar.class);
				Calendar endCal = eventdetail.getValueMap().get("end", Calendar.class);
				
				json.put("location", properties.get("location",""));
				json.put("speaker", properties.get("speaker",""));
				json.put("locationMap", properties.get("locationMap",""));
				json.put("host", properties.get("host",""));
				json.put("unixstarttime", EventHelper.getUnixStartTime(eventdetail));
				json.put("isExternal", properties.get("isExternal","false"));
				json.put("dayOfWeek", getDayOfWeek(startCal));
				json.put("eventDate", getDate(startCal));
				json.put("startTime", getTime(startCal));
				json.put("endTime", getTime(endCal));
				
				/* Ignoring legacy contact info as that causes invalid JSON data */
				json.put("contact", EventHelper.getFormattedContactInfo(
					properties.get("contactName",""), 
					properties.get("contactEmail",""), 
					properties.get("contactPhone","")
				));
				
			}
			
		} catch (JSONException e) {
			
			log.error(DEBUG_PREFIX + "Problem creating JSON for page " + p.getPath(), e);
			
		}
		
		return json;
	}
	
	/*
	 * Returns a String representing the day of the week this Calendar
	 * represents, or "Today" if the calendar represents this particular day
	 */
	private String getDayOfWeek(Calendar c) {
		
		Calendar now = Calendar.getInstance();
		
		if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
				c.get(Calendar.MONTH) == now.get(Calendar.MONTH) &&
				c.get(Calendar.DAY_OF_MONTH) == now.get(Calendar.DAY_OF_MONTH)) {
			return "Today";
		} else {
			return EventHelper.getDayOfWeek(c);
		}
		
	}
	
	/*
	 * Returns a String representing the hour:minute this Calendar represents,
	 * along with "a.m." or "p.m." if the time is not noon
	 */
	private String getTime(Calendar c) {
		
		StringBuffer sb = new StringBuffer();
		
		sb.append(EventHelper.getTime(c));
		if (!sb.toString().equals("noon")) {
			sb.append(" ");
			sb.append(EventHelper.getAmPm(c));
		}
		
		return sb.toString();
		
	}
	
	/*
	 * Returns a String representing the month and day-of-month this Calendar
	 * represents
	 */
	private String getDate(Calendar c) {
		
		StringBuffer sb = new StringBuffer();
		
		sb.append(EventHelper.getMonth(c));
		sb.append(" ");
		sb.append(EventHelper.getDate(c));
		
		return sb.toString();
		
	}
	
	/*
	 * Adds to the query map the list of tags that were passed into the 
	 * component at run time via the tags argument, then returns that map
	 */
	private HashMap<String, String> addTagFilter(HashMap<String, String> map) {
		
		String[] theTags = tags.split(",");
		
		map.put("group.p.or", "true");
		Integer count = 1;
		
		for (String s : theTags) {
			if (isTag(s)) {
				map.put("group." + count.toString() + "_tagid", s);
				map.put("group." + count.toString() + "_tagid.property", PN_TAG_PROPERTY);
				count++;
			}
		}

		return map;
	}
	
	/*
	 * Returns true if there is a tag with the passed tagID, false otherwise
	 */
	private Boolean isTag(String tagID) {
		
		if (tagManager.resolve(tagID) != null) {
			return true;
		} else {
			log.warn(DEBUG_PREFIX + "Tag ID passed in tags argument is invalid: " + tagID);
			return false;
		}
		
	}

}
