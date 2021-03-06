/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/
package eu.stratosphere.nephele.streaming.taskmanager.qosmanager;

import java.io.IOException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import eu.stratosphere.nephele.configuration.GlobalConfiguration;
import eu.stratosphere.nephele.executiongraph.ExecutionVertexID;
import eu.stratosphere.nephele.io.DistributionPattern;
import eu.stratosphere.nephele.io.GateID;
import eu.stratosphere.nephele.io.channels.ChannelID;
import eu.stratosphere.nephele.jobgraph.JobID;
import eu.stratosphere.nephele.streaming.JobGraphLatencyConstraint;
import eu.stratosphere.nephele.streaming.LatencyConstraintID;
import eu.stratosphere.nephele.streaming.message.StreamChainAnnounce;
import eu.stratosphere.nephele.streaming.message.action.EdgeQosReporterConfig;
import eu.stratosphere.nephele.streaming.message.action.VertexQosReporterConfig;
import eu.stratosphere.nephele.streaming.message.qosreport.EdgeLatency;
import eu.stratosphere.nephele.streaming.message.qosreport.EdgeStatistics;
import eu.stratosphere.nephele.streaming.message.qosreport.QosReport;
import eu.stratosphere.nephele.streaming.message.qosreport.VertexLatency;
import eu.stratosphere.nephele.streaming.taskmanager.qosmanager.buffers.BufferSizeManager;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.EdgeQosData;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosEdge;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosGate;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosGraph;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosGroupVertex;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosReporterID;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.QosVertex;
import eu.stratosphere.nephele.streaming.taskmanager.qosmodel.VertexQosData;

/**
 * Wrapper class around a Qos graph used by a Qos manager. A Qos model is a
 * state machine that first assembles a Qos graph from
 * {@link EdgeQosReporterConfig} and {@link VertexQosReporterConfig} objects and
 * then continuously adds Qos report data to the Qos graph. It can then be used
 * to search for violated Qos constraints inside the Qos graph.
 * 
 * @author Bjoern Lohrmann
 */
public class QosModel {

	public enum State {
		/**
		 * If the Qos model is empty, it means that the internal Qos graph does
		 * not contain any group vertices.
		 */
		EMPTY,

		/**
		 * If the Qos model is shallow, it means that the internal Qos graph
		 * does contain group vertices, but at least one group vertex has no
		 * members. Members are added by vertex/edge announcements piggybacked
		 * inside of Qos reports from the Qos reporters.
		 */
		SHALLOW,

		/**
		 * If the Qos model is ready, it means that the internal Qos graph does
		 * contain group vertices, and each group vertex has at least one member
		 * vertex. A transition back to SHALLOW is possible, when new shallow
		 * group vertices are merged into the Qos graph. Members may still be
		 * added by vertex/edge announcements piggybacked inside of Qos reports
		 * at any time.
		 */
		READY
	}

	private State state;

	/**
	 * A sparse graph that is assembled from two sources: (1) The (shallow)
	 * group-level Qos graphs received as part of the Qos manager roles
	 * delivered by job manager. (2) The vertex/edge reporter announcements
	 * delivered by (possibly many) Qos reporters, once the vertex/edge produces
	 * Qos data (which is may never happen, especially for some edges).
	 */
	private QosGraph qosGraph;

	/**
	 * A dummy Qos report that buffers vertex/edge announcements for later
	 * processing.
	 */
	private QosReport announcementBuffer;

	/**
	 * All gates of the Qos graph mapped by their ID.
	 */
	private HashMap<GateID, QosGate> gatesByGateId;

	/**
	 * All Qos vertices of the Qos graph mapped by their ID.
	 */
	private HashMap<ExecutionVertexID, QosVertex> vertexByID;

	/**
	 * All Qos edges of the Qos graph mapped by their source channel ID.
	 */
	private HashMap<ChannelID, QosEdge> edgeBySourceChannelID;

	private HashMap<LatencyConstraintID, QosLogger> qosLoggers;

	public QosModel(JobID jobID) {
		this.state = State.EMPTY;
		this.announcementBuffer = new QosReport(jobID);
		this.gatesByGateId = new HashMap<GateID, QosGate>();
		this.vertexByID = new HashMap<ExecutionVertexID, QosVertex>();
		this.edgeBySourceChannelID = new HashMap<ChannelID, QosEdge>();
		this.qosLoggers = new HashMap<LatencyConstraintID, QosLogger>();
	}

	public void mergeShallowQosGraph(QosGraph shallowQosGraph) {
		if (this.qosGraph == null) {
			this.qosGraph = shallowQosGraph;
		} else {
			this.qosGraph.merge(shallowQosGraph);
		}

		this.tryToProcessBufferedAnnouncements();
	}

	public boolean isReady() {
		return this.state == State.READY;
	}

	public boolean isEmpty() {
		return this.state == State.EMPTY;
	}

	public boolean isShallow() {
		return this.state == State.SHALLOW;
	}

	public void processQosReport(QosReport report) {
		switch (this.state) {
		case READY:
			if (report.hasAnnouncements()
					|| this.announcementBuffer.hasAnnouncements()) {
				this.bufferAndTryToProcessAnnouncements(report);
			}
			this.processQosRecords(report);
			break;
		case SHALLOW:
			this.bufferAndTryToProcessAnnouncements(report);
			break;
		case EMPTY:
			this.bufferAnnouncements(report);
			break;
		}
	}

	public void processStreamChainAnnounce(StreamChainAnnounce announce) {

		QosVertex currentVertex = this.vertexByID.get(announce.getChainBegin()
				.getVertexID());

		while (!currentVertex.getID().equals(
				announce.getChainEnd().getVertexID())) {

			if (currentVertex.getGroupVertex().getNumberOfOutputGates() != 1) {
				throw new RuntimeException(
						"Cannot chain task that has more than one output gate");
			}

			if (currentVertex.getGroupVertex().getForwardEdge(0)
					.getDistributionPattern() != DistributionPattern.POINTWISE) {

				throw new RuntimeException(
						"Cannot chain task with non-POINTIWSE distribution pattern.");
			}

			QosEdge forwardEdge = currentVertex.getOutputGate(0).getEdge(0);
			forwardEdge.getQosData().setIsInChain(true);
			currentVertex = forwardEdge.getInputGate().getVertex();
		}
	}

	private void processQosRecords(QosReport report) {
		long now = System.currentTimeMillis();
		this.processVertexLatencies(report.getVertexLatencies(), now);
		this.processEdgeStatistics(report.getEdgeStatistics(), now);
		this.processEdgeLatencies(report.getEdgeLatencies(), now);
	}

	private void processVertexLatencies(
			Collection<VertexLatency> vertexLatencies, long now) {

		for (VertexLatency vertexLatency : vertexLatencies) {
			QosReporterID.Vertex reporterID = vertexLatency.getReporterID();

			QosGate inputGate = this.gatesByGateId.get(reporterID
					.getInputGateID());
			QosGate outputGate = this.gatesByGateId.get(reporterID
					.getOutputGateID());

			if (inputGate != null) {
				VertexQosData qosData = inputGate.getVertex().getQosData();
				qosData.addLatencyMeasurement(inputGate.getGateIndex(),
						outputGate.getGateIndex(), now,
						vertexLatency.getVertexLatency());
			}
		}
	}

	private void processEdgeStatistics(
			Collection<EdgeStatistics> edgeStatistics, long now) {
		for (EdgeStatistics edgeStatistic : edgeStatistics) {
			QosReporterID.Edge reporterID = edgeStatistic.getReporterID();
			QosEdge edge = this.edgeBySourceChannelID.get(reporterID
					.getSourceChannelID());

			if (edge != null) {
				edge.getQosData().addOutputChannelStatisticsMeasurement(now,
						edgeStatistic);
			}
		}
	}

	private void processEdgeLatencies(Collection<EdgeLatency> edgeLatencies,
			long now) {

		for (EdgeLatency edgeLatency : edgeLatencies) {
			QosReporterID.Edge reporterID = edgeLatency.getReporterID();
			QosEdge edge = this.edgeBySourceChannelID.get(reporterID
					.getSourceChannelID());

			if (edge != null) {
				edge.getQosData().addLatencyMeasurement(now,
						edgeLatency.getEdgeLatency());
			}
		}
	}

	private void bufferAndTryToProcessAnnouncements(QosReport report) {
		this.bufferAnnouncements(report);
		this.tryToProcessBufferedAnnouncements();
	}

	private void tryToProcessBufferedAnnouncements() {
		this.tryToProcessBufferedVertexReporterAnnouncements();
		this.tryToProcessBufferedEdgeReporterAnnouncements();

		if (this.qosGraph.isShallow()) {
			this.state = State.SHALLOW;
		} else {
			this.state = State.READY;
		}
	}

	private void tryToProcessBufferedEdgeReporterAnnouncements() {
		Iterator<EdgeQosReporterConfig> vertexIter = this.announcementBuffer
				.getEdgeQosReporterAnnouncements().iterator();

		while (vertexIter.hasNext()) {
			EdgeQosReporterConfig toProcess = vertexIter.next();

			QosGate outputGate = this.gatesByGateId.get(toProcess
					.getOutputGateID());
			QosGate inputGate = this.gatesByGateId.get(toProcess
					.getInputGateID());

			if (inputGate != null && outputGate != null) {
				this.assembleQosEdgeFromReporterConfig(toProcess, outputGate,
						inputGate);
				vertexIter.remove();
			}
		}
	}

	private void assembleQosEdgeFromReporterConfig(
			EdgeQosReporterConfig toProcess, QosGate outputGate,
			QosGate inputGate) {

		if (this.edgeBySourceChannelID.get(toProcess.getSourceChannelID()) == null) {
			QosEdge edge = toProcess.toQosEdge();
			edge.setOutputGate(outputGate);
			edge.setInputGate(inputGate);
			edge.setQosData(new EdgeQosData(edge));
			this.edgeBySourceChannelID.put(edge.getSourceChannelID(), edge);
		}
	}

	private void tryToProcessBufferedVertexReporterAnnouncements() {
		Iterator<VertexQosReporterConfig> vertexIter = this.announcementBuffer
				.getVertexQosReporterAnnouncements().iterator();

		while (vertexIter.hasNext()) {
			VertexQosReporterConfig toProcess = vertexIter.next();

			QosGroupVertex groupVertex = this.qosGraph
					.getGroupVertexByID(toProcess.getGroupVertexID());

			if (groupVertex != null) {
				this.assembleQosVertexFromReporterConfig(toProcess, groupVertex);
				vertexIter.remove();
			}
		}
	}

	/**
	 * Assembles a member vertex for the given group vertex, using the reporter
	 * config data.
	 */
	private void assembleQosVertexFromReporterConfig(
			VertexQosReporterConfig toProcess, QosGroupVertex groupVertex) {

		int memberIndex = toProcess.getMemberIndex();
		QosVertex memberVertex = groupVertex.getMember(memberIndex);

		// if the reporter config has a previously unknown member
		// vertex, add it to the group vertex
		if (memberVertex == null) {
			memberVertex = toProcess.toQosVertex();
			memberVertex.setQosData(new VertexQosData(memberVertex));
			groupVertex.setGroupMember(memberVertex);
			this.vertexByID.put(memberVertex.getID(), memberVertex);
		}

		int inputGateIndex = toProcess.getInputGateIndex();
		int outputGateIndex = toProcess.getOutputGateIndex();

		// if the reporter config has a previously unknown input gate
		// for us, add it to the vertex
		if (inputGateIndex != -1
				&& memberVertex.getInputGate(inputGateIndex) == null) {

			QosGate gate = toProcess.toInputGate();
			memberVertex.setInputGate(gate);
			this.gatesByGateId.put(gate.getGateID(), gate);
		}

		// if the reporter config has a previously unknown output gate
		// for us, add it to the vertex
		if (outputGateIndex != -1
				&& memberVertex.getOutputGate(outputGateIndex) == null) {

			QosGate gate = toProcess.toOutputGate();
			memberVertex.setOutputGate(gate);
			this.gatesByGateId.put(gate.getGateID(), gate);
		}

		// only if the reporter has a valid input/output gate combination,
		// prepare for reports on that combination
		if (inputGateIndex != -1 && outputGateIndex != -1) {
			memberVertex.getQosData().prepareForReportsOnGateCombination(
					inputGateIndex, outputGateIndex);
		}
	}

	private void bufferAnnouncements(QosReport report) {
		// bufferEdgeLatencies(report.getEdgeLatencies());
		// bufferEdgeStatistics(report.getEdgeStatistics());
		// bufferVertexLatencies(report.getVertexLatencies());
		this.bufferEdgeQosReporterAnnouncements(report
				.getEdgeQosReporterAnnouncements());
		this.bufferVertexQosReporterAnnouncements(report
				.getVertexQosReporterAnnouncements());
	}

	private void bufferVertexQosReporterAnnouncements(
			Collection<VertexQosReporterConfig> vertexQosReporterAnnouncements) {

		for (VertexQosReporterConfig reporterConfig : vertexQosReporterAnnouncements) {
			this.announcementBuffer.announceVertexQosReporter(reporterConfig);
		}
	}

	private void bufferEdgeQosReporterAnnouncements(
			List<EdgeQosReporterConfig> edgeQosReporterAnnouncements) {

		for (EdgeQosReporterConfig reporterConfig : edgeQosReporterAnnouncements) {
			this.announcementBuffer
					.addEdgeQosReporterAnnouncement(reporterConfig);
		}
	}

	public void findQosConstraintViolations(
			QosConstraintViolationListener listener) {

		for (JobGraphLatencyConstraint constraint : this.qosGraph
				.getConstraints()) {
			QosLogger logger = this.qosLoggers.get(constraint.getID());
			if (logger == null) {
				try {
					logger = new QosLogger(
							constraint.getID(),
							this.qosGraph,
							GlobalConfiguration
									.getLong(
											BufferSizeManager.QOSMANAGER_ADJUSTMENTINTERVAL_KEY,
											BufferSizeManager.DEFAULT_ADJUSTMENTINTERVAL));
					this.qosLoggers.put(constraint.getID(), logger);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}

			QosConstraintViolationFinder constraintViolationFinder = new QosConstraintViolationFinder(
					constraint.getID(), this.qosGraph, listener, logger);
			constraintViolationFinder.findSequencesWithViolatedQosConstraint();
			try {
				logger.logLatencies();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}
}
