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

import com.coverity.ws.v6.DefectInstanceDataObj;
import com.coverity.ws.v6.EventDataObj;
import com.coverity.ws.v6.MergedDefectDataObj;
import com.coverity.ws.v6.ProjectDataObj;
import org.junit.Before;
import org.junit.Test;
import org.sonar.api.component.ResourcePerspectives;
import org.sonar.api.config.Settings;
import org.sonar.api.profiles.RulesProfile;
import org.sonar.api.resources.Project;
import org.sonar.plugins.coverity.ws.CIMClient;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class CoveritySensorTest {
    Settings settings;
    RulesProfile profile;
    ResourcePerspectives resourcePerspectives;
    CoveritySensor sensor;

    @Before
    public void setUp() throws Exception {
        settings = mock(Settings.class);
        profile = mock(RulesProfile.class);
        resourcePerspectives = mock(ResourcePerspectives.class);

        sensor = new CoveritySensor(settings, profile, resourcePerspectives);
    }

    @Test
    public void testShouldExecuteOnProject() throws Exception {
        Project project = mock(Project.class);
        //assertTrue(sensor.shouldExecuteOnProject(project));
    }

    @Test
    public void testGetIssueMessage() throws Exception {
        //
    }

    @Test
    public void testGetCheckerProperties() throws Exception {
        //
    }

    @Test
    public void testGetDefectURL() throws Exception {
        CIMClient instance = mock(CIMClient.class);
        ProjectDataObj projectObj = mock(ProjectDataObj.class);
        MergedDefectDataObj mddo = mock(MergedDefectDataObj.class);

        String target = "http://&&HOST&&:999999/sourcebrowser.htm?projectId=888888#mergedDefectId=777777";

        when(instance.getHost()).thenReturn("&&HOST&&");
        when(instance.getPort()).thenReturn(999999);
        when(projectObj.getProjectKey()).thenReturn(888888L);
        when(mddo.getCid()).thenReturn(777777L);
        String url = sensor.getDefectURL(instance, projectObj, mddo);

        assertEquals(target, url);
    }

    @Test
    public void testGetMainEvent() throws Exception {
        DefectInstanceDataObj dido = new DefectInstanceDataObj();

        EventDataObj em = new EventDataObj();
        em.setMain(true);

        dido.getEvents().add(em);

        int n = 10;
        for(int i = 0; i < n; i++) {
            dido.getEvents().add(new EventDataObj());
        }

        Collections.swap(dido.getEvents(), 0, n / 2);

        EventDataObj result = sensor.getMainEvent(dido);
        assertEquals("Found wrong event", em, result);
    }

    @Test
    public void testGetResourceForFile() throws Exception {
        //
    }
}
