/*******************************************************************************
 * Copyright (c) 2013 IBM Corporation and others 
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 * 
 * Contributors:
 * IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.orion.server.cf.commands;

import javax.servlet.http.HttpServletResponse;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.orion.server.cf.CFProtocolConstants;
import org.eclipse.orion.server.cf.objects.App;
import org.eclipse.orion.server.cf.objects.Target;
import org.eclipse.orion.server.cf.utils.MultiServerStatus;
import org.eclipse.orion.server.core.ServerStatus;
import org.eclipse.osgi.util.NLS;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PushAppCommand extends AbstractCFCommand {

	private final Logger logger = LoggerFactory.getLogger("org.eclipse.orion.server.cf"); //$NON-NLS-1$

	private String commandName;
	private App app;
	private boolean force;

	public PushAppCommand(Target target, App app, boolean force) {
		super(target);
		this.commandName = "Push application"; //$NON-NLS-1$
		this.app = app;
		this.force = force;
	}

	@Override
	protected ServerStatus _doIt() {
		/* multi server status */
		MultiServerStatus status = new MultiServerStatus();

		try {
			if (app.getSummaryJSON() == null) {

				/* create new application */
				CreateApplicationCommand createApplication = new CreateApplicationCommand(target, app, force);
				ServerStatus jobStatus = (ServerStatus) createApplication.doIt(); /* FIXME: unsafe type cast */
				status.add(jobStatus);
				if (!jobStatus.isOK())
					return status;

				JSONObject appResp = jobStatus.getJsonData();

				/* bind route to application */
				BindRouteCommand bindRoute = new BindRouteCommand(target, app);
				ServerStatus multijobStatus = (ServerStatus) bindRoute.doIt(); /* FIXME: unsafe type cast */
				status.add(multijobStatus);
				if (!multijobStatus.isOK())
					return status;

				/* upload project contents */
				UploadBitsCommand uploadBits = new UploadBitsCommand(target, app);
				multijobStatus = (ServerStatus) uploadBits.doIt(); /* FIXME: unsafe type cast */
				status.add(multijobStatus);
				if (!multijobStatus.isOK())
					return status;

				/* bind application specific services */
				BindServicesCommand bindServices = new BindServicesCommand(target, app);
				multijobStatus = (ServerStatus) bindServices.doIt(); /* FIXME: unsafe type cast */
				status.add(multijobStatus);
				if (!multijobStatus.isOK())
					return status;

				/* craft command result */
				JSONObject result = new JSONObject();
				result.put("Target", target.toJSON());
				if (target.getManageUrl() != null)
					result.put("ManageUrl", target.getManageUrl().toString() + "#/resources/appGuid=" + app.getGuid() + "&orgGuid=" + target.getOrg().getGuid() + "&spaceGuid=" + target.getSpace().getGuid());
				result.put("App", appResp);
				result.put("Domain", bindRoute.getDomainName());
				result.put("Route", bindRoute.getRoute());

				status.add(new ServerStatus(Status.OK_STATUS, HttpServletResponse.SC_OK, result));
				return status;
			}

			/* set known application guid */
			app.setGuid(app.getSummaryJSON().getString(CFProtocolConstants.V2_KEY_GUID));

			/* upload project contents */
			UploadBitsCommand uploadBits = new UploadBitsCommand(target, app);
			ServerStatus multijobStatus = (ServerStatus) uploadBits.doIt(); /* TODO: unsafe type cast */
			status.add(multijobStatus);
			if (!multijobStatus.isOK())
				return status;

			/* restart the application */
			RestartAppCommand restartApp = new RestartAppCommand(target, app);
			multijobStatus = (ServerStatus) restartApp.doIt(); /* TODO: unsafe type cast */
			status.add(multijobStatus);
			if (!multijobStatus.isOK())
				return status;

			/* craft command result */
			JSONObject route = app.getSummaryJSON().getJSONArray("routes").getJSONObject(0);
			JSONObject result = new JSONObject();

			result.put("Target", target.toJSON());
			if (target.getManageUrl() != null)
				result.put("ManageUrl", target.getManageUrl().toString() + "#/resources/appGuid=" + app.getGuid() + "&orgGuid=" + target.getOrg().getGuid() + "&spaceGuid=" + target.getSpace().getGuid());

			result.put("App", app.getSummaryJSON());
			result.put("Domain", route.getJSONObject("domain").getString("name"));
			result.put("Route", route);

			status.add(new ServerStatus(Status.OK_STATUS, HttpServletResponse.SC_OK, result));
			return status;

		} catch (Exception e) {
			String msg = NLS.bind("An error occured when performing operation {0}", commandName); //$NON-NLS-1$
			logger.error(msg, e);
			status.add(new ServerStatus(IStatus.ERROR, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, msg, e));
			return status;
		}
	}
}
