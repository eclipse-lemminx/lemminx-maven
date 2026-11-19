/*******************************************************************************
 * Copyright (c) 2026 Mykola Nikishov and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *******************************************************************************/
package org.eclipse.lemminx.extensions.maven;

import org.eclipse.lemminx.commons.progress.ProgressMonitor;
import org.eclipse.lemminx.commons.progress.ProgressSupport;
import org.eclipse.lsp4j.WorkDoneProgressCreateParams;
import org.eclipse.lsp4j.WorkDoneProgressNotification;

import java.util.concurrent.CompletableFuture;

public final class NoopProgressSupport implements ProgressSupport {
	@Override
	public ProgressMonitor createProgressMonitor() {
		return new NoopProgressMonitor(this);
	}

	@Override
	public ProgressMonitor createProgressMonitor(String progressId) {
		return createProgressMonitor();
	}

	@Override
	public boolean isWorkDoneProgressSupported() {
		return false;
	}

	@Override
	public CompletableFuture<Void> createProgress(WorkDoneProgressCreateParams params) {
		return null;
	}

	@Override
	public void notifyProgress(String progressId, WorkDoneProgressNotification notification) {
	}

	private static class NoopProgressMonitor extends ProgressMonitor {

		public NoopProgressMonitor(ProgressSupport progressSupport) {
			super(progressSupport);
		}

		@Override
		public void begin(String title, String message, Integer percentage, Boolean cancellable) {
		}

		@Override
		public void report(String message, Integer percentage, Boolean cancellable) {
		}

		@Override
		public void end(String message) {
		}

		@Override
		public void checkCanceled() {
		}

		@Override
		public boolean isCanceled() {
			return false;
		}
	}
}
