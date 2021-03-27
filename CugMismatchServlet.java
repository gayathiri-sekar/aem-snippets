package com.ford.dealer.content.core.servlet;

import com.day.cq.dam.api.AssetManager;
import com.day.cq.search.PredicateGroup;
import com.day.cq.search.Query;
import com.day.cq.search.QueryBuilder;
import com.day.cq.search.result.SearchResult;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.jackrabbit.api.security.authorization.PrincipalSetPolicy;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.servlets.HttpConstants;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.json.JSONObject;
import org.osgi.framework.Constants;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.ford.dealer.content.core.util.ACLUtil;
import com.ford.dealer.content.core.util.ResourceResolverUtil;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.security.AccessControlManager;
import javax.jcr.security.AccessControlPolicy;
import javax.servlet.Servlet;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component(service = Servlet.class, property = { Constants.SERVICE_DESCRIPTION + "=CUG Mismatch Servlet",
		"sling.servlet.methods=" + HttpConstants.METHOD_GET, "sling.servlet.paths=" + "/bin/cug-mismatch-report" })

public class CugMismatchServlet extends SlingSafeMethodsServlet {

	private static final long serialVersionUID = 1L;
	private static final Logger LOGGER = LoggerFactory.getLogger(CugMismatchServlet.class);
	private static final String SERVICE_USER = "fmc.metadata";

	@Reference
	private transient ResourceResolverFactory resourceResolverFactory;

	@Override
	protected void doGet(final SlingHttpServletRequest request, final SlingHttpServletResponse response)
			throws IOException {

		response.setContentType("text/csv");
		response.setHeader("Content-Disposition", "attachment; filename=\"cugreport.csv\"");
		String type = "";

		try {

			ResourceResolver resourceResolver = ResourceResolverUtil.getServiceResourceResolver(resourceResolverFactory,
					SERVICE_USER);

			StringBuilder report = new StringBuilder();
			report.append("Path,").append("Folder/Asset,").append("Fmcgroupslist,").append("CUGS,").append("Same/Not");

			String path = request.getParameter("path");

			QueryBuilder queryBuilder = resourceResolver.adaptTo(QueryBuilder.class);

			if (queryBuilder != null) {
				Map<String, String> parameterMap = new HashMap<>();
				parameterMap.put("path", path);
				parameterMap.put("type", "nt:unstructured");
				parameterMap.put("p.limit", "-1");
				parameterMap.put("property", "fmcgroupslist");
				parameterMap.put("property.operation", "exists");

				PredicateGroup predicateGroup = PredicateGroup.create(parameterMap);
				Session session = resourceResolver.adaptTo(Session.class);
				Query query = queryBuilder.createQuery(predicateGroup, session);
				SearchResult searchResult = query.getResult();
				for (Iterator<Resource> resourceIterator = searchResult.getResources(); resourceIterator.hasNext();) {

					Resource resource = resourceIterator.next();
					String resourcePath = resource.getPath();
					Resource parentResource = resource.getParent();

					// Find if Folder or Asset
					if (StringUtils.equals(parentResource.getValueMap().get("jcr:primaryType", String.class),
							"sling:Folder")) {
						type = "Folder";
					} else {
						type = "Asset";
					}

					// Get fmcgroupslistproperty
					List<String> fmcgroupsList = new ArrayList<>();
					Node node = resource.adaptTo(Node.class);
					Property propVal = node.getProperty("fmcgroupslist");
					Value[] values = propVal.getValues();
					for (Value val : values) {
						fmcgroupsList.add(val.getString());
					}

					// Get rep:cugPolicy principal Names
					List<String> cugLists = new ArrayList<>();
					cugLists = getCugs(resourceResolver, path);

					// Check if 2 Lists are same
					Collections.sort(fmcgroupsList);
					Collections.sort(cugLists);
					boolean isEqual = fmcgroupsList.equals(cugLists);

					// Append to csv file
					report.append("\n");
					report.append(resourcePath + ",").append(type + ",").append(String.join("||", fmcgroupsList))
							.append(String.join("||", cugLists) + ",").append(isEqual);
				}
			}

			LOGGER.info("========Report========={}", report);
			OutputStream outputStream = response.getOutputStream();
			outputStream.write(report.toString().getBytes());
			outputStream.flush();
			outputStream.close();
		} catch (Exception e) {
			LOGGER.info("==={}", e);
		}
	}

	private List<String> getCugs(ResourceResolver resolver, String path) {

		List<String> cugList = new ArrayList<>();

		try {
			Set<Principal> hashSet = new HashSet<Principal>();
			Session session = resolver.adaptTo(Session.class);
			AccessControlManager acMgr = session.getAccessControlManager();
			List<PrincipalSetPolicy> cugPolicies = new ArrayList<PrincipalSetPolicy>();

			AccessControlPolicy[] acpArr = acMgr.getPolicies(path);
			if (acpArr != null) {
				for (AccessControlPolicy policy : acpArr) {
					if (policy instanceof PrincipalSetPolicy) {
						cugPolicies.add((PrincipalSetPolicy) policy);
					}
				}
			}

			for (PrincipalSetPolicy cugPolicy : cugPolicies) {
				hashSet = cugPolicy.getPrincipals();
				for (Principal p : hashSet) {
					cugList.add(p.getName().toString());
				}
			}
		} catch (Exception e) {
			LOGGER.info("==={}", e);
		}
		return cugList;
	}

}
