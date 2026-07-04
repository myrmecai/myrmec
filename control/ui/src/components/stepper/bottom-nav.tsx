import { Button } from "@/components/ui/button";
import type { Step } from "@stepperize/react";
import React from "react";
import { StepperActionType, StepperContext, type WizardBottomProps, type WizardTopProps } from "./types";
import i18n from "@/localization";
import { cn } from "@/lib/utils";

export function StepperBottomNav<TSteps extends readonly Step[]>({ activeStepRef, useStepperHook, utils, 
  labelPreviousButton = i18n.t('common.buttons.previous'), labelNextButton = i18n.t('common.buttons.next'), labelFinishButton = i18n.t('common.buttons.save'), onFinish,
  ...props }: WizardBottomProps & React.ComponentProps<"div">) {
  const { state: stepperState, setState: setStepperState } = React.useContext(StepperContext)!;
  const stepper = useStepperHook();
  // const currentIndex = utils.getIndex(stepper.current.id);
  const allStepsValid = stepper.all.every((step) => {
    const metadata = stepperState.steps[step.id as string];
    return metadata?.isValid !== false;
  });

  const doNext = () => {
    activeStepRef?.current?.saveState();
    setStepValidity();
    if (stepper.isLast && onFinish) {
      onFinish(activeStepRef?.current?.getState());
      return;
    }
    stepper.next();
  }
  const doBack = () => {
    // back is allowed always
    activeStepRef?.current?.saveState();
    setStepValidity();
    stepper.prev();
  }
  const setStepValidity = () => {
    setStepperState({
      type: StepperActionType.SetStepValidity, payload: { stepId: stepper.current.id, isValid: activeStepRef.current?.isValid() || false }
    });
  }
  return <>

    <div className={cn("flex flex-col", props.className)} {...props}>
      <div className="flex-row flex">
        <Button
          className="mr-4"
          variant="outline"
          onClick={doBack}
          disabled={stepper.isFirst || stepperState.isBusy}
        >
          {labelPreviousButton}
        </Button>
        <Button
          onClick={doNext}
          disabled={((stepper.isLast && !onFinish) || ( stepper.isLast && onFinish && !allStepsValid)) || stepperState.isBusy}
        >
          {stepper.isLast && onFinish ? labelFinishButton : labelNextButton}
        </Button>
      </div >
    </div >

  </>
}