/*
 * Coverity Sonar Plugin
 * Copyright (C) 2014 Coverity, Inc.
 * support@coverity.com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02
 */

package org.sonar.plugins.coverity.ws;

import com.coverity.ws.v6.ConfigurationService;
import com.coverity.ws.v6.ConfigurationServiceService;
import com.coverity.ws.v6.CovRemoteServiceException_Exception;
import com.coverity.ws.v6.DefectService;
import com.coverity.ws.v6.DefectServiceService;
import com.coverity.ws.v6.MergedDefectDataObj;
import com.coverity.ws.v6.MergedDefectFilterSpecDataObj;
import com.coverity.ws.v6.MergedDefectsPageDataObj;
import com.coverity.ws.v6.PageSpecDataObj;
import com.coverity.ws.v6.ProjectDataObj;
import com.coverity.ws.v6.ProjectFilterSpecDataObj;
import com.coverity.ws.v6.ProjectIdDataObj;
import com.coverity.ws.v6.StreamDataObj;
import com.coverity.ws.v6.StreamDefectDataObj;
import com.coverity.ws.v6.StreamDefectFilterSpecDataObj;
import com.coverity.ws.v6.StreamFilterSpecDataObj;
import com.coverity.ws.v6.StreamIdDataObj;

import javax.xml.namespace.QName;
import javax.xml.ws.BindingProvider;
import javax.xml.ws.handler.Handler;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents one Coverity Integrity Manager server. 
 * Abstracts functions like getting streams and defects.
 */
public class CIMClient {
	
	public static Map<Long, StreamDefectDataObj> sddos=null;  //stream defect data object
	public static List<MergedDefectDataObj> defects=null;  //defects
	public static ProjectDataObj project=null;
	
    public static final String COVERITY_WS_VERSION = "v6";
    public static final String COVERITY_NAMESPACE = "http://ws.coverity.com/" + COVERITY_WS_VERSION;
    public static final String CONFIGURATION_SERVICE_WSDL = "/ws/" + COVERITY_WS_VERSION + "/configurationservice?wsdl";
    public static final String DEFECT_SERVICE_WSDL = "/ws/" + COVERITY_WS_VERSION + "/defectservice?wsdl";

    private static final int GET_STREAM_DEFECTS_MAX_CIDS = 100;

    /**
     * The host name for the CIM server
     */
    private final String host;
    /**
     * The port for the CIM server (this is the HTTP port and not the data port)
     */
    private final int port;
    /**
     * Username for connecting to the CIM server
     */
    private final String user;
    /**
     * Password for connecting to the CIM server
     */
    private final String password;
    /**
     * Use SSL
     */
    private final boolean useSSL;
    /**
     * cached webservice port for Defect service
     */
    private transient DefectServiceService defectServiceService;
    /**
     * cached webservice port for Configuration service
     */
    private transient ConfigurationServiceService configurationServiceService;
    private transient Map<String, Long> projectKeys;

    public CIMClient(String host, int port, String user, String password, boolean ssl) {
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        this.useSSL = ssl;
    }

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getUser() {
        return user;
    }

    public String getPassword() {
        return password;
    }

    public boolean isUseSSL() {
        return useSSL;
    }

    /**
     * The root URL for the CIM instance
     *
     * @return a url
     * @throws java.net.MalformedURLException should not happen if host is valid
     */
    public URL getURL() throws MalformedURLException {
        return new URL(useSSL ? "https" : "http", host, port, "/");
    }

    /**
     * Returns a Defect service client
     */
    public DefectService getDefectService() throws IOException {
        synchronized(this) {
            if(defectServiceService == null) {
                defectServiceService = new DefectServiceService(
                        new URL(getURL(), DEFECT_SERVICE_WSDL),
                        new QName(COVERITY_NAMESPACE, "DefectServiceService"));
            }
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            DefectService defectService = defectServiceService.getDefectServicePort();
            attachAuthenticationHandler((BindingProvider)defectService);

            return defectService;
        } finally {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    /**
     * Attach an authentication handler to the web service, that uses the configured user and password
     */
    private void attachAuthenticationHandler(BindingProvider service) {
        service.getBinding().setHandlerChain(Arrays.<Handler>asList(new ClientAuthenticationHandlerWSS(user, password)));
    }

    /**
     * Returns a Configuration service client
     */
    public ConfigurationService getConfigurationService() throws IOException {
        synchronized(this) {
            if(configurationServiceService == null) {
                // Create a Web Services port to the server
                configurationServiceService = new ConfigurationServiceService(
                        new URL(getURL(), CONFIGURATION_SERVICE_WSDL),
                        new QName(COVERITY_NAMESPACE, "ConfigurationServiceService"));
            }
        }

        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try {
            ConfigurationService configurationService = configurationServiceService.getConfigurationServicePort();
            attachAuthenticationHandler((BindingProvider)configurationService);

            return configurationService;
        } finally {
            Thread.currentThread().setContextClassLoader(cl);
        }
    }

    public List<MergedDefectDataObj> getDefects(String streamId, List<Long> defectIds) throws IOException, CovRemoteServiceException_Exception {
        MergedDefectFilterSpecDataObj filterSpec1 = new MergedDefectFilterSpecDataObj();
        StreamIdDataObj stream = new StreamIdDataObj();
        stream.setName(streamId);
        PageSpecDataObj pageSpec = new PageSpecDataObj();
        pageSpec.setPageSize(2500);

        List<MergedDefectDataObj> result = new ArrayList<MergedDefectDataObj>();
        int defectCount = 0;
        MergedDefectsPageDataObj defects = null;
        do {
            pageSpec.setStartIndex(defectCount);
            defects = getDefectService().getMergedDefectsForStreams(Arrays.asList(stream), filterSpec1, pageSpec);
            for(MergedDefectDataObj defect : defects.getMergedDefects()) {
                if(defectIds.contains(defect.getCid())) {
                    result.add(defect);
                }
            }
            defectCount += defects.getMergedDefects().size();
        } while(defectCount < defects.getTotalNumberOfRecords());

        return result;
    }

    public List<MergedDefectDataObj> getDefects(String project) throws IOException, CovRemoteServiceException_Exception {
    	if(CIMClient.defects!=null)return CIMClient.defects;
    	MergedDefectFilterSpecDataObj filterSpec = new MergedDefectFilterSpecDataObj();
        ProjectIdDataObj projectId = new ProjectIdDataObj();
        projectId.setName(project);
        PageSpecDataObj pageSpec = new PageSpecDataObj();
        pageSpec.setPageSize(2500);
		
		/*Adding coverity filters*/
		
		//Set component exclude flag
		filterSpec.setComponentIdExclude(false);
		
		//Set Status Name List
		filterSpec.getStatusNameList().add("Triaged");
        filterSpec.getStatusNameList().add("New");    
		
		//Set Action name list
        filterSpec.getActionNameList().add("Undecided");
        filterSpec.getActionNameList().add("Fix Required");
        filterSpec.getActionNameList().add("Fix Submitted");
        filterSpec.getActionNameList().add("Modeling Required");
        
        List<MergedDefectDataObj> result = new ArrayList<MergedDefectDataObj>();
        int defectCount = 0;
        MergedDefectsPageDataObj defects = null;
        do {
            pageSpec.setStartIndex(defectCount);
            defects = getDefectService().getMergedDefectsForProject(projectId, filterSpec, pageSpec);
            result.addAll(defects.getMergedDefects());
            defectCount += defects.getMergedDefects().size();
        } while(defectCount < defects.getTotalNumberOfRecords());
        CIMClient.defects=result;
        return result;
    }

    public ProjectDataObj getProject(String projectId) throws IOException, CovRemoteServiceException_Exception {
    	if(CIMClient.project!=null)return CIMClient.project;
        ProjectFilterSpecDataObj filterSpec = new ProjectFilterSpecDataObj();
        filterSpec.setNamePattern(projectId);
        List<ProjectDataObj> projects = getConfigurationService().getProjects(filterSpec);
        if(projects.size() == 0) {
            return null;
        } else {
        	CIMClient.project=projects.get(0);
            return CIMClient.project;
        }
    }

    public Long getProjectKey(String projectId) throws IOException, CovRemoteServiceException_Exception {
        if(projectKeys == null) {
            projectKeys = new ConcurrentHashMap<String, Long>();
        }

        Long result = projectKeys.get(projectId);
        if(result == null) {
            result = getProject(projectId).getProjectKey();
            projectKeys.put(projectId, result);
        }
        return result;
    }

    public List<ProjectDataObj> getProjects() throws IOException, CovRemoteServiceException_Exception {
        return getConfigurationService().getProjects(new ProjectFilterSpecDataObj());
    }

    public List<StreamDataObj> getStaticStreams(String projectId) throws IOException, CovRemoteServiceException_Exception {
        ProjectDataObj project = getProject(projectId);
        List<StreamDataObj> result = new ArrayList<StreamDataObj>();
        for(StreamDataObj stream : project.getStreams()) {
            result.add(stream);
        }

        return result;
    }

    public StreamDataObj getStream(String streamId) throws IOException, CovRemoteServiceException_Exception {
        StreamFilterSpecDataObj filter = new StreamFilterSpecDataObj();
        filter.setNamePattern(streamId);

        List<StreamDataObj> streams = getConfigurationService().getStreams(filter);
        if(streams.isEmpty()) {
            return null;
        } else {
            return streams.get(0);
        }
    }

    public Map<Long, StreamDefectDataObj> getStreamDefectsForMergedDefects(List<MergedDefectDataObj> defects) throws IOException, CovRemoteServiceException_Exception {
    	if(CIMClient.sddos!=null)return CIMClient.sddos;
    	Map<Long, MergedDefectDataObj> cids = new HashMap<Long, MergedDefectDataObj>();

        Map<Long, StreamDefectDataObj> sddos = new HashMap<Long, StreamDefectDataObj>();

        for(MergedDefectDataObj mddo : defects) {
            cids.put(mddo.getCid(), mddo);
        }

        StreamDefectFilterSpecDataObj filter = new StreamDefectFilterSpecDataObj();
        filter.setIncludeDefectInstances(true);

        List<Long> cidList = new ArrayList<Long>(cids.keySet());

        for(int i = 0; i < cidList.size(); i += GET_STREAM_DEFECTS_MAX_CIDS) {
            List<Long> slice = cidList.subList(i, i + Math.min(GET_STREAM_DEFECTS_MAX_CIDS, cidList.size() - i));

            List<StreamDefectDataObj> temp = getDefectService().getStreamDefects(slice, filter);

            for(StreamDefectDataObj sddo : temp) {
                sddos.put(sddo.getCid(), sddo);
            }
        }
        CIMClient.sddos=sddos;
        return sddos;
    }
}
