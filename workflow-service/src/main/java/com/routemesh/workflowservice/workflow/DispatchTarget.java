package com.routemesh.workflowservice.workflow;

/** Dağıtıma çıkan gönderinin o anki durumu ve taşınacak ağırlığı. */
public record DispatchTarget(String status, long weightGrams) {
}
