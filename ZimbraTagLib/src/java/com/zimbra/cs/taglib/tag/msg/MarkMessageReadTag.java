/*
 * ***** BEGIN LICENSE BLOCK *****
 * Zimbra Collaboration Suite Server
 * Copyright (C) 2006, 2007, 2009, 2010 Zimbra, Inc.
 * 
 * The contents of this file are subject to the Zimbra Public License
 * Version 1.3 ("License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 * http://www.zimbra.com/license.
 * 
 * Software distributed under the License is distributed on an "AS IS"
 * basis, WITHOUT WARRANTY OF ANY KIND, either express or implied.
 * ***** END LICENSE BLOCK *****
 */
package com.zimbra.cs.taglib.tag.msg;

import com.zimbra.common.service.ServiceException;
import com.zimbra.cs.taglib.bean.ZActionResultBean;
import com.zimbra.cs.taglib.tag.ZimbraSimpleTag;
import com.zimbra.client.ZMailbox.ZActionResult;

import javax.servlet.jsp.JspException;
import javax.servlet.jsp.PageContext;
import javax.servlet.jsp.JspTagException;

public class MarkMessageReadTag extends ZimbraSimpleTag {

    private String mId;
    private boolean mRead;
    private String mVar;

    public void setVar(String var) { this.mVar = var; }
    public void setId(String id) { this.mId = id; }
    public void setRead(boolean read) { this.mRead = read; }

    public void doTag() throws JspException {
        try {
            ZActionResult result = getMailbox().markMessageRead(mId, mRead);
            getJspContext().setAttribute(mVar, new ZActionResultBean(result),  PageContext.PAGE_SCOPE);
        } catch (ServiceException e) {
            throw new JspTagException(e);
        }
    }
}