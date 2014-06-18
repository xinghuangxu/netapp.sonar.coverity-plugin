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

package org.sonar.plugins.coverity.batch;

import com.coverity.ws.v6.CovRemoteServiceException_Exception;
import com.coverity.ws.v6.DefectInstanceDataObj;
import com.coverity.ws.v6.EventDataObj;
import com.coverity.ws.v6.MergedDefectDataObj;
import com.coverity.ws.v6.ProjectDataObj;
import com.coverity.ws.v6.StreamDefectDataObj;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonar.api.batch.Sensor;
import org.sonar.api.batch.SensorContext;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.issue.Issuable;
import org.sonar.api.issue.Issue;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.api.resources.ProjectFileSystem;
import org.sonar.api.resources.Resource;
import org.sonar.api.rules.ActiveRule;
import org.sonar.api.rules.Rule;
import org.sonar.plugins.coverity.CoverityPlugin;
import org.sonar.plugins.coverity.util.CoverityUtil;
import org.sonar.plugins.coverity.ws.CIMClient;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

public class CoveritySensor implements Sensor {
    private static final Logger LOG = LoggerFactory.getLogger(CoveritySensor.class); //logger
    private final ResourcePerspectives resourcePerspectives;  //resource perspective constructor injection
    private Settings settings;  //settings
    private RulesProfile profile;  //profile for rules

    public CoveritySensor(Settings settings, RulesProfile profile, ResourcePerspectives resourcePerspectives) {
        this.settings = settings;
        this.profile = profile;
        this.resourcePerspectives = resourcePerspectives;
    }

    public boolean shouldExecuteOnProject(Project project) {
        boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);
        int active = profile.getActiveRulesByRepository(CoverityPlugin.REPOSITORY_KEY + "-" + project.getLanguageKey()).size();
        return enabled && active > 0;
    }

    public void analyse(Project project, SensorContext sensorContext) {
        boolean enabled = settings.getBoolean(CoverityPlugin.COVERITY_ENABLE);

        LOG.info(CoverityPlugin.COVERITY_ENABLE + "=" + enabled);

        if(!enabled) {
            return;
        }

        //make sure to use the right SAAJ library. The one included with some JREs is missing a required file (a
        // LocalStrings bundle)
        ClassLoader oldCL = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        System.setProperty("javax.xml.soap.MetaFactory", "com.sun.xml.messaging.saaj.soap.SAAJMetaFactoryImpl");

        String host = settings.getString(CoverityPlugin.COVERITY_CONNECT_HOSTNAME);
        int port = settings.getInt(CoverityPlugin.COVERITY_CONNECT_PORT);
        String user = settings.getString(CoverityPlugin.COVERITY_CONNECT_USERNAME);
        String password = settings.getString(CoverityPlugin.COVERITY_CONNECT_PASSWORD);
        boolean ssl = settings.getBoolean(CoverityPlugin.COVERITY_CONNECT_SSL);

        String covProject = settings.getString(CoverityPlugin.COVERITY_PROJECT);
        String stripPrefix = settings.getString(CoverityPlugin.COVERITY_PREFIX);

        CIMClient instance = new CIMClient(host, port, user, password, ssl);

        //find the configured project
        ProjectDataObj covProjectObj = null;
        try {
            covProjectObj = instance.getProject(covProject);
            LOG.info("Found project: " + covProject + " (" + covProjectObj.getProjectKey() + ")");

            if(covProjectObj == null) {
                LOG.error("Couldn't find project: " + covProject);
                Thread.currentThread().setContextClassLoader(oldCL);
                return;
            }
        } catch(Exception e) {
            LOG.error("Error while trying to find project, check connection settings: " + covProject);
            Thread.currentThread().setContextClassLoader(oldCL);
            return;
        }

        LOG.debug(profile.toString());
        for(ActiveRule ar : profile.getActiveRulesByRepository(CoverityPlugin.REPOSITORY_KEY + "-" + project.getLanguageKey())) {
            LOG.debug(ar.toString());
        }

        try {
            LOG.info("Fetching defects for project: " + covProject);
            List<MergedDefectDataObj> defects = instance.getDefects(covProject);

            Map<Long, StreamDefectDataObj> streamDefects = instance.getStreamDefectsForMergedDefects(defects);

            LOG.info("Found " + streamDefects.size() + " defects");

            for(MergedDefectDataObj mddo : defects) {
                String filePath = mddo.getFilePathname();
                if (stripPrefix != null && !stripPrefix.isEmpty() && filePath.startsWith(stripPrefix))
                    filePath = "./" + filePath.substring(stripPrefix.length());
                Resource res = getResourceForFile(filePath, project);

                if(res == null) {
                    LOG.info("Cannot find the file '" + filePath + "' in "+ project.getFileSystem().getSourceDirs() + ", skipping defect (CID " + mddo.getCid() + ")");
                    continue;
                }

                for(DefectInstanceDataObj dido : streamDefects.get(mddo.getCid()).getDefectInstances()) {
                    //find the main event, so we can use its line number
                    EventDataObj mainEvent = getMainEvent(dido);
                    
                    

                    Issuable issuable = resourcePerspectives.as(Issuable.class, res);

                    org.sonar.api.resources.Language lang = res.getLanguage();
                    if (lang == null) {
                        lang = project.getLanguage();
                    }
                    org.sonar.api.rule.RuleKey rk = CoverityUtil.getRuleKey(lang.getKey(), dido);
                    ActiveRule ar = profile.getActiveRule(rk.repository(), rk.rule());

                    LOG.debug("mainEvent=" + mainEvent);
                    LOG.debug("issuable=" + issuable);
                    LOG.debug("ar=" + ar);
                    if(mainEvent != null && issuable != null && ar != null) {
                        LOG.debug("instance=" + instance);
                        LOG.debug("ar.getRule()=" + ar.getRule());
                        LOG.debug("covProjectObj=" + covProjectObj);
                        LOG.debug("mddo=" + mddo);
                        LOG.debug("dido=" + dido);
                        LOG.debug("ar.getRule().getDescription()=" + ar.getRule().getDescription());
                        String message = getIssueMessage(instance, ar.getRule(), covProjectObj, mddo, dido);

                        Issue issue = issuable.newIssueBuilder()
                                .ruleKey(ar.getRule().ruleKey())
                                .line(mainEvent.getLineNumber())
                                .message(message)
                                .attribute("coverity-issue-id", mddo.getCid().toString())
                                .build();
                        LOG.debug("issue=" + issue);
                        boolean result = issuable.addIssue(issue);
                        LOG.debug("result=" + result);
                    } else {
                        LOG.info("Couldn't create issue: " + mddo.getCid());
                    }
                }
            }
        } catch(Exception e) {
            LOG.error("Error fetching defects", e);
        }

        Thread.currentThread().setContextClassLoader(oldCL);
    }

    protected String getIssueMessage(CIMClient instance, Rule rule, ProjectDataObj covProjectObj, MergedDefectDataObj mddo, DefectInstanceDataObj dido) throws CovRemoteServiceException_Exception, IOException {
        String url = getDefectURL(instance, covProjectObj, mddo);

        LOG.debug("rule:" + rule);
        LOG.debug("description:" + rule.getDescription());

        return rule.getDescription() + "\n\nView in Coverity Connect: \n" + url;
    }

    protected String getDefectURL(CIMClient instance, ProjectDataObj covProjectObj, MergedDefectDataObj mddo) {
        return String.format("%s://%s:%d/sourcebrowser.htm?projectId=%s#mergedDefectId=%d",
                instance.isUseSSL() ? "https" : "http", instance.getHost(), instance.getPort(), covProjectObj.getProjectKey(), mddo.getCid());
    }

    protected EventDataObj getMainEvent(DefectInstanceDataObj dido) {
        for(EventDataObj edo : dido.getEvents()) {
            if(edo.isMain()) {
                return edo;
            }
        }
        return null;
    }

    protected Resource getResourceForFile(String filePath, Project module) throws IOException {
	   // String newPath = filePath.replaceAll("\\/u/coverity/ccm_wa/symbios/RAIDCore-covTrunk/dev_e10_820_\\w{4}-68.20.99.99+", "/u/chockali/ccm_wa/symbios/RAIDCore-3278_ce/IMTdev_cov_e10_820_5501-68.20.32.78");
    	String source_path = settings.getString(CoverityPlugin.COVERITY_SOURCE_PATH);
    	if(source_path!=null){
    		filePath = filePath.replaceAll("\\/u/covdev/ccm_wa/symbios/RAIDCore-cdTrunk/dev_e10_820_\\w{4}-68.20.99.99+", source_path);
    	}
		LOG.info("filePath: " + filePath);
        File f = new File(filePath);
        Resource ret;
        ret = org.sonar.api.resources.File.fromIOFile(f, module);
        if(ret == null) {
            LOG.info("Cannot find file: "+filePath);
        }
        return ret;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName();
    }
}
