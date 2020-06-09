/*******************************************************************************
 * Copyright (c) 2015 Takari, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *      Anton Tanasenko. - initial API and implementation
 *      Andrew Obuchowicz - Copy & modification from m2e
 *******************************************************************************/
package org.eclipse.lemminx.maven;

import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.Objects;

// TODO: Make Maven bug about moving this upstream
public class MojoParameter {

	private String name;

	private String type;

	private boolean required;

	private String description;

	private String expression;

	private String defaultValue;

	private List<MojoParameter> nested;

	private boolean multiple;

	private boolean map;

	private Type paramType;

	public MojoParameter(String name, String type, List<MojoParameter> parameters) {
		this.name = name;
		this.type = type;
		nested = parameters;
	}
	public MojoParameter(String name, Type paramType, List<MojoParameter> parameters) {
		this(name, PlexusConfigHelper.getTypeDisplayName(paramType), parameters);
		this.setParamType(paramType);
	}

	public MojoParameter(String name, Type paramType, MojoParameter parameter) {
		this(name, PlexusConfigHelper.getTypeDisplayName(paramType), Collections.singletonList(parameter));
		this.setParamType(paramType);
	}

	public MojoParameter(String name, String type) {
		this(name, type, Collections.<MojoParameter>emptyList());
	}

	public MojoParameter(String name, Type paramType) {
		this(name, PlexusConfigHelper.getTypeDisplayName(paramType));
		this.setParamType(paramType);
	}

	public MojoParameter multiple() {
		this.multiple = true;
		return this;
	}

	public MojoParameter map() {
		this.map = true;
		return this;
	}

	public boolean isMultiple() {
		return multiple;
	}

	public boolean isMap() {
		return this.map;
	}

	public List<MojoParameter> getNestedParameters() {
		return nested == null ? Collections.<MojoParameter>emptyList() : Collections.unmodifiableList(nested);
	}
	
	public List<MojoParameter> getFlattenedNestedParameters(){
		Deque<MojoParameter> parametersToCheck = new ArrayDeque<>();
		List<MojoParameter> nestedParameters = new ArrayList<MojoParameter>();
		for (MojoParameter node : getNestedParameters()) {
			parametersToCheck.push(node);
		}
		while (!parametersToCheck.isEmpty()) {
			MojoParameter parameter = parametersToCheck.pop();
			if (!parameter.getNestedParameters().isEmpty()) {
				for (MojoParameter nestedParameter : parameter.getNestedParameters()) {
					parametersToCheck.push(nestedParameter);
				}
			} 
			nestedParameters.add(parameter);
		}
		return nestedParameters;
	}

	public String getName() {
		return this.name;
	}

	public String getType() {
		return this.type;
	}

	public boolean isRequired() {
		return this.required;
	}

	public void setRequired(boolean required) {
		this.required = required;
	}

	public String getDescription() {
		return this.description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getExpression() {
		return this.expression;
	}

	public void setExpression(String expression) {
		this.expression = expression;
	}

	public String getDefaultValue() {
		return this.defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		this.defaultValue = defaultValue;
	}

	public String toString() {
		return name + "{" + type + "}"
				+ (getNestedParameters().isEmpty() ? ""
						: " nested: ("
								+ getNestedParameters().stream().map(Object::toString).collect(Collectors.joining(", "))
								+ ")");
	}

	public MojoParameter getNestedParameter(String name) {
		List<MojoParameter> params = getNestedParameters();
		if (params.size() == 1) {
			MojoParameter param = params.get(0);
			if (param.isMultiple()) {
				return param;
			}
		}

		for (MojoParameter p : params) {
			if (p.getName().equals(name)) {
				return p;
			}
		}
		return null;
	}

	public MojoParameter getContainer(String[] path) {
		if (path == null || path.length == 0) {
			return this;
		}

		MojoParameter param = this;
		int i = 0;
		while (param != null && i < path.length) {
			param = param.getNestedParameter(path[i]);
			i++;
		}

		if (param == null) {
			return null;
		}

		return param;
	}
	
	public Type getParamType() {
		return paramType;
	}

	public void setParamType(Type paramType) {
		this.paramType = paramType;
	}

	@Override
	public int hashCode() {
		return Objects.hashCode(required, map, multiple, type, getNestedParameters().size(), name, expression, description,
				defaultValue);
	}

	@Override
	public boolean equals(Object obj) {
		if (obj == null || !(obj instanceof MojoParameter)) {
			return false;
		}
		MojoParameter otherMojo = (MojoParameter) obj;

		return (this.isRequired() == otherMojo.isRequired()) && (this.isMap() == otherMojo.isMap())
				&& (this.isMultiple() == otherMojo.isMultiple()) && (Objects.equal(this.getType(), otherMojo.getType()))
				&& (Objects.equal(this.getNestedParameters(), otherMojo.getNestedParameters()))
				&& (Objects.equal(this.getName(), otherMojo.getName())
						&& (Objects.equal(this.getExpression(), otherMojo.getExpression())
								&& (Objects.equal(this.getDescription(), otherMojo.getDescription()))))
				&& (Objects.equal(this.getDefaultValue(), otherMojo.getDefaultValue()));
	}

}
