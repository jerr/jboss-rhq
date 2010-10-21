/*
 * RHQ Management Platform
 * Copyright (C) 2005-2010 Red Hat, Inc.
 * All rights reserved.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 675 Mass Ave, Cambridge, MA 02139, USA.
 */
package org.rhq.enterprise.gui.coregui.client.inventory.resource.discovery;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.allen_sauer.gwt.log.client.Log;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.smartgwt.client.data.DSRequest;
import com.smartgwt.client.data.DSResponse;
import com.smartgwt.client.data.DataSource;
import com.smartgwt.client.data.fields.DataSourceTextField;
import com.smartgwt.client.types.DSDataFormat;
import com.smartgwt.client.types.DSProtocol;
import com.smartgwt.client.widgets.tree.TreeGrid;
import com.smartgwt.client.widgets.tree.TreeNode;

import org.rhq.core.domain.authz.Permission;
import org.rhq.core.domain.resource.InventoryStatus;
import org.rhq.core.domain.resource.Resource;
import org.rhq.core.domain.resource.ResourceType;
import org.rhq.core.domain.util.PageControl;
import org.rhq.core.domain.util.PageOrdering;
import org.rhq.enterprise.gui.coregui.client.CoreGUI;
import org.rhq.enterprise.gui.coregui.client.gwt.AuthorizationGWTServiceAsync;
import org.rhq.enterprise.gui.coregui.client.gwt.GWTServiceLookup;
import org.rhq.enterprise.gui.coregui.client.gwt.ResourceGWTServiceAsync;

/**
 * @author Greg Hinkle
 */
public class AutodiscoveryQueueDataSource extends DataSource {

    private int unlimited = -1;
    private int maximumPlatformsToDisplay = -1;
    private ResourceGWTServiceAsync resourceService = GWTServiceLookup.getResourceService();
    private AuthorizationGWTServiceAsync authorizationService = GWTServiceLookup.getAuthorizationService();
    public static final String NO_MANAGE_INVENTORY_PERMS_EMPTY_MESSAGE = "(Required manage inventory permissions missing. See Administrator to change)";
    public static final String EMPTY_MESSAGE = "No items to show";
    private TreeGrid dataContainerReference = null;
    private static final Permission MANAGE_INVENTORY = Permission.MANAGE_INVENTORY;

    public AutodiscoveryQueueDataSource() {
        setClientOnly(false);
        setDataProtocol(DSProtocol.CLIENTCUSTOM);
        setDataFormat(DSDataFormat.CUSTOM);

        DataSourceTextField idField = new DataSourceTextField("id", "ID");
        idField.setPrimaryKey(true);

        DataSourceTextField parentIdField = new DataSourceTextField("parentId", "Parent ID");
        parentIdField.setForeignKey("id");

        DataSourceTextField resourceNameField = new DataSourceTextField("name", "Resource Name");

        DataSourceTextField resourceKeyField = new DataSourceTextField("resourceKey", "Resource Key");

        DataSourceTextField resourceTypeField = new DataSourceTextField("typeName", "Type");

        DataSourceTextField descriptionField = new DataSourceTextField("description", "Description");

        DataSourceTextField timestampField = new DataSourceTextField("ctime", "Discovery Time");

        DataSourceTextField statusField = new DataSourceTextField("status", "Inventory Status");

        setFields(idField, parentIdField, resourceNameField, resourceKeyField, resourceTypeField, descriptionField,
            statusField, timestampField);
    }

    public AutodiscoveryQueueDataSource(TreeGrid treeGrid) {
        this();
        this.dataContainerReference = treeGrid;
    }

    protected Object transformRequest(DSRequest request) {
        DSResponse response = new DSResponse();
        response.setAttribute("clientContext", request.getAttributeAsObject("clientContext"));
        // Asume success
        response.setStatus(0);
        switch (request.getOperationType()) {
        case FETCH:
            executeFetch(request, response);
            break;
        default:
            break;
        }

        return request.getData();
    }

    protected void executeFetch(final DSRequest request, final DSResponse response) {
        final PageControl pc = getPageControl(request);

        final HashSet<InventoryStatus> statuses = new HashSet<InventoryStatus>();

        String statusesString = request.getCriteria().getAttributeAsString("statuses");
        if (statusesString != null) {
            if ("New".equals(statusesString)) {
                statuses.add(InventoryStatus.NEW);
            } else if ("Ignored".equals(statusesString)) {
                statuses.add(InventoryStatus.IGNORED);
            } else {
                statuses.add(InventoryStatus.NEW);
                statuses.add(InventoryStatus.IGNORED);
            }
        } else {
            statuses.add(InventoryStatus.NEW);
        }

        //determine if has manage inventory perms, if so then chain and proceed with getting discovered resources
        authorizationService.checkUserGlobalPermission(MANAGE_INVENTORY, new AsyncCallback<Boolean>() {
            public void onSuccess(Boolean hasManageInventoryPermissions) {
                if (hasManageInventoryPermissions) {
                    if (dataContainerReference != null) {
                        dataContainerReference.setEmptyMessage(EMPTY_MESSAGE);
                    }
                    resourceService.getQueuedPlatformsAndServers(statuses, pc,
                        new AsyncCallback<Map<Resource, List<Resource>>>() {
                            public void onFailure(Throwable caught) {
                                CoreGUI.getErrorHandler().handleError("Failed to load inventory discovery queue",
                                    caught);
                            }

                            public void onSuccess(Map<Resource, List<Resource>> result) {
                                response.setData(buildNodes(result));
                                processResponse(request.getRequestId(), response);
                            }
                        });
                } else {
                    Log.debug("(User does not have required managed inventory permissions. " + EMPTY_MESSAGE);
                    response.setTotalRows(0);
                    if (dataContainerReference != null) {
                        Log.trace("Setting better empty container message." + NO_MANAGE_INVENTORY_PERMS_EMPTY_MESSAGE);
                        dataContainerReference.setEmptyMessage(NO_MANAGE_INVENTORY_PERMS_EMPTY_MESSAGE);
                    }
                    processResponse(request.getRequestId(), response);
                }
            }

            public void onFailure(Throwable caught) {
                Log.error("Unable to determine whether if user has manage inventory permissions - check server logs.");
            }
        });

    }

    private TreeNode[] buildNodes(Map<Resource, List<Resource>> result) {

        ArrayList<ResourceTreeNode> nodes = new ArrayList<ResourceTreeNode>();
        for (Resource platform : result.keySet()) {
            nodes.add(new ResourceTreeNode(platform));

            for (Resource child : result.get(platform)) {
                ResourceTreeNode childNode = new ResourceTreeNode(child);
                childNode.setIsFolder(false);
                nodes.add(childNode);
            }
        }

        TreeNode[] treeNodes = nodes.toArray(new TreeNode[nodes.size()]);
        return treeNodes;
    }

    /**
     * Returns a prepopulated PageControl based on the provided DSRequest. This will set sort fields,
     * pagination, but *not* filter fields.
     *
     * @param request the request to turn into a page control
     * @return the page control for passing to criteria and other queries
     */
    protected PageControl getPageControl(DSRequest request) {
        // Initialize paging.
        PageControl pageControl;
        if (getMaximumPlatformsToDisplay() > -1) {//using default            
            pageControl = new PageControl(0, getMaximumPlatformsToDisplay());
        } else {
            pageControl = new PageControl(0, unlimited);
        }

        // Initialize sorting.
        String sortBy = request.getAttribute("sortBy");
        if (sortBy != null) {
            String[] sorts = sortBy.split(",");
            for (String sort : sorts) {
                PageOrdering ordering = (sort.startsWith("-")) ? PageOrdering.DESC : PageOrdering.ASC;
                String columnName = (ordering == PageOrdering.DESC) ? sort.substring(1) : sort;
                pageControl.addDefaultOrderingField(columnName, ordering);
            }
        }

        return pageControl;
    }

    public static class ResourceTreeNode extends TreeNode {

        private Resource resource;

        private ResourceTreeNode(Resource resource) {
            this.resource = resource;

            String id = String.valueOf(resource.getId());
            String parentId = resource.getParentResource() == null ? null : String.valueOf((resource
                .getParentResource().getId()));

            setID(id);
            setParentID(parentId);

            setAttribute("id", id);
            setAttribute("parentId", parentId);
            setAttribute("name", resource.getName());
            setAttribute("typeName", resource.getResourceType().getName());
            setAttribute("resourceKey", resource.getResourceKey());
            setAttribute("description", resource.getDescription());
            setAttribute("ctime", new Date(resource.getCtime()));
            setAttribute("status", resource.getInventoryStatus().name());
        }

        public Resource getResource() {
            return resource;
        }

        public void setResource(Resource resource) {
            this.resource = resource;
        }

        public ResourceType getResourceType() {
            return resource.getResourceType();
        }

        public String getParentId() {
            return getAttribute("parentId");
        }
    }

    public int getMaximumPlatformsToDisplay() {
        return maximumPlatformsToDisplay;
    }

    public void setMaximumPlatformsToDisplay(int maximumPlatformsToDisplay) {
        this.maximumPlatformsToDisplay = maximumPlatformsToDisplay;
    }

}
