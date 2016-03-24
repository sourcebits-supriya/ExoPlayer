/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.android.exoplayer;

import com.google.android.exoplayer.util.Assertions;

import android.util.Pair;

import java.io.IOException;
import java.util.IdentityHashMap;

/**
 * Combines multiple {@link SampleSource} instances.
 */
public final class MultiSampleSource implements SampleSource {

  private final SampleSource[] sources;
  private final IdentityHashMap<TrackStream, Integer> trackStreamSourceIndices;

  private int state;
  private long durationUs;
  private TrackGroupArray trackGroups;

  public MultiSampleSource(SampleSource... sources) {
    this.sources = sources;
    trackStreamSourceIndices = new IdentityHashMap<>();
    state = STATE_UNPREPARED;
  }

  @Override
  public int getState() {
    return state;
  }

  @Override
  public boolean prepare(long positionUs) throws IOException {
    Assertions.checkState(state == SampleSource.STATE_UNPREPARED);
    boolean sourcesPrepared = true;
    for (SampleSource source : sources) {
      if (source.getState() == SampleSource.STATE_UNPREPARED) {
        sourcesPrepared &= source.prepare(positionUs);
      }
    }
    if (!sourcesPrepared) {
      return false;
    }
    durationUs = 0;
    int totalTrackGroupCount = 0;
    for (SampleSource source : sources) {
      totalTrackGroupCount += source.getTrackGroups().length;
      if (durationUs != C.UNKNOWN_TIME_US) {
        long sourceDurationUs = source.getDurationUs();
        durationUs = sourceDurationUs == C.UNKNOWN_TIME_US
            ? C.UNKNOWN_TIME_US : Math.max(durationUs, sourceDurationUs);
      }
    }
    TrackGroup[] trackGroupArray = new TrackGroup[totalTrackGroupCount];
    int trackGroupIndex = 0;
    for (SampleSource source : sources) {
      int sourceTrackGroupCount = source.getTrackGroups().length;
      for (int j = 0; j < sourceTrackGroupCount; j++) {
        trackGroupArray[trackGroupIndex++] = source.getTrackGroups().get(j);
      }
    }
    trackGroups = new TrackGroupArray(trackGroupArray);
    state = STATE_SELECTING_TRACKS;
    return true;
  }

  @Override
  public TrackGroupArray getTrackGroups() {
    return trackGroups;
  }

  @Override
  public void startTrackSelection() {
    Assertions.checkState(state == STATE_READING);
    state = STATE_SELECTING_TRACKS;
    for (SampleSource source : sources) {
      source.startTrackSelection();
    }
  }

  @Override
  public TrackStream selectTrack(TrackSelection selection, long positionUs) {
    Assertions.checkState(state == STATE_SELECTING_TRACKS);
    Pair<Integer, Integer> sourceAndGroup = getSourceAndTrackGroupIndices(selection.group);
    TrackStream trackStream = sources[sourceAndGroup.first].selectTrack(
        new TrackSelection(sourceAndGroup.second, selection.getTracks()), positionUs);
    trackStreamSourceIndices.put(trackStream, sourceAndGroup.first);
    return trackStream;
  }

  @Override
  public void unselectTrack(TrackStream trackStream) {
    Assertions.checkState(state == STATE_SELECTING_TRACKS);
    int sourceIndex = trackStreamSourceIndices.remove(trackStream);
    sources[sourceIndex].unselectTrack(trackStream);
  }

  @Override
  public void endTrackSelection(long positionUs) {
    Assertions.checkState(state == STATE_SELECTING_TRACKS);
    state = STATE_READING;
    for (SampleSource source : sources) {
      source.endTrackSelection(positionUs);
    }
  }

  @Override
  public void continueBuffering(long positionUs) {
    for (SampleSource source : sources) {
      source.continueBuffering(positionUs);
    }
  }

  @Override
  public void seekToUs(long positionUs) {
    for (SampleSource source : sources) {
      source.seekToUs(positionUs);
    }
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @Override
  public long getBufferedPositionUs() {
    long bufferedPositionUs = durationUs != C.UNKNOWN_TIME_US ? durationUs : Long.MAX_VALUE;
    for (SampleSource source : sources) {
      long rendererBufferedPositionUs = source.getBufferedPositionUs();
      if (rendererBufferedPositionUs == C.UNKNOWN_TIME_US) {
        return C.UNKNOWN_TIME_US;
      } else if (rendererBufferedPositionUs == C.END_OF_SOURCE_US) {
        // This source is fully buffered.
      } else {
        bufferedPositionUs = Math.min(bufferedPositionUs, rendererBufferedPositionUs);
      }
    }
    return bufferedPositionUs == Long.MAX_VALUE ? C.UNKNOWN_TIME_US : bufferedPositionUs;
  }

  @Override
  public void release() {
    for (SampleSource source : sources) {
      source.release();
    }
    state = STATE_RELEASED;
  }

  private Pair<Integer, Integer> getSourceAndTrackGroupIndices(int group) {
    int totalTrackGroupCount = 0;
    for (int i = 0; i < sources.length; i++) {
      int sourceTrackGroupCount = sources[i].getTrackGroups().length;
      if (group < totalTrackGroupCount + sourceTrackGroupCount) {
        return Pair.create(i, group - totalTrackGroupCount);
      }
      totalTrackGroupCount += sourceTrackGroupCount;
    }
    throw new IndexOutOfBoundsException();
  }

}
