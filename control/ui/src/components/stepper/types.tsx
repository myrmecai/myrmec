import type { ContextStateComponentRef } from "@/types/common-types";
import { type Step, type Utils, type Stepper } from '@stepperize/core';
import React from "react";

export type MutableSteps<T extends readonly Step[]> = [...T];
export type UseStepperHook = () => Stepper<Step[]>;
export type StepperUtils = Utils<Step[]>;
export type DefineStepReturn = {
  useStepper: UseStepperHook;
  steps: Step[];
  utils: any;
  Scoped: any;
}
export type WizardTopProps = {
  activeStepRef: React.RefObject<ContextStateComponentRef | null>;
  useStepperHook: UseStepperHook;
  utils: Utils;
};
export type WizardBottomProps = WizardTopProps & {
  labelPreviousButton?: string;
  labelNextButton?: string;
  labelFinishButton?: string;
  onFinish?: (state: any) => void;
};
export type StepState = {
  isValid: boolean;
};
export type StepperState = {
  isBusy?: boolean | undefined;
  steps: {
    [stepId: string]: StepState | undefined;
  };
};
export enum StepperActionType {
  SetBusy = 'setBusy',
  SetStepValidity = 'setStepValidity',
}
export type StepperStateAction = {
  type: StepperActionType;
  payload: boolean | { stepId: string; isValid: boolean; };

}

const reducer: React.Reducer<StepperState, Partial<StepperStateAction>> = (state, action) => {
  switch (action.type) {
    case StepperActionType.SetBusy:
      return {
        ...state,
        isBusy: action.payload as boolean,
      };
    case StepperActionType.SetStepValidity:
      const { stepId, isValid } = action.payload as { stepId: string; isValid: boolean; };
      return {
        ...state,
        steps: {
          ...state.steps,
          [stepId]: { isValid },
        },
      };
    default: {
      throw Error('Unknown action: ' + action.type);
    }
  };
};
const initialState: StepperState = { isBusy: false, steps: {} };
export const StepperContext = React.createContext<{
  state: StepperState;
  setState: React.Dispatch<Partial<StepperStateAction>>;
} | null>({ state: initialState, setState: () => { } });

export function StepperContextProvider({ children }: { children: React.ReactNode }) {

  const [state, setState] = React.useReducer(reducer, initialState);

  return (
    <StepperContext value={{ state, setState }}>
      {children}
    </StepperContext>
  );
}