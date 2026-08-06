import {
    AlertDialog,
    AlertDialogAction,
    AlertDialogCancel,
    AlertDialogContent,
    AlertDialogDescription,
    AlertDialogFooter,
    AlertDialogHeader,
    AlertDialogTitle,
} from "@/components/ui/alert-dialog";
import {
    MessageCircleQuestionIcon,
    CircleXIcon,
    InfoIcon,
    MessageCircleWarningIcon,
} from "lucide-react";

import { createRoot } from "react-dom/client";
import i18n from "i18next";

// Decode HTML entities like &amp; to &
const decodeHtmlEntities = (text: string): string => {
    const textarea = document.createElement("textarea");
    textarea.innerHTML = text;
    return textarea.value;
};

type CloseHandler = (result: boolean) => void;
type ConfirmDialogProps = {
    title: string;
    message: string;
    cancelLabel?: string;
    confirmLabel?: string;
    severity?: "info" | "warning" | "error";
    type?: "info" | "question" | "warning" | "error";
    component?: React.ReactNode;
};
const MessageIcon = ({
    severity,
    type,
}: {
    severity: "info" | "warning" | "error";
    type: "info" | "question" | "warning" | "error";
}) => {
    switch (type) {
        case "question":
            return <MessageCircleQuestionIcon className={severity} />;
        case "info":
            return <InfoIcon className={severity} />;
        case "warning":
            return <MessageCircleWarningIcon className={severity} />;
        case "error":
            return <CircleXIcon className={severity} />;
        default:
            return null;
    }
};
const ConfirmDialog = ({
    onClose,
    ...props
}: { onClose: CloseHandler } & ConfirmDialogProps) => {
    const {
        title,
        message,
        cancelLabel = "No",
        confirmLabel = "Yes",
        severity = "info",
        type = "question",
    } = props;
    return (
        <AlertDialog open={true} onOpenChange={() => onClose(false)}>
            <AlertDialogContent data-testid="confirm-dialog">
                <AlertDialogHeader>
                    <AlertDialogTitle>{title}</AlertDialogTitle>
                    {message && <AlertDialogDescription className="flex items-center gap-2">
                        <MessageIcon severity={severity} type={type} />
                        {decodeHtmlEntities(message)}
                    </AlertDialogDescription>
                    }

                </AlertDialogHeader>
                {props.component && (
                    props.component
                )}
                <AlertDialogFooter>
                    {cancelLabel && <AlertDialogCancel onClick={() => onClose(false)}>
                        {cancelLabel}
                    </AlertDialogCancel>
                    }
                    {confirmLabel && <AlertDialogAction onClick={() => onClose(true)}>
                        {confirmLabel}
                    </AlertDialogAction>
                    }
                </AlertDialogFooter>
            </AlertDialogContent>
        </AlertDialog>
    );
};
class DialogService {
    showConfirmDialog({ ...props }: ConfirmDialogProps): Promise<boolean> {
        var { title, message, cancelLabel, confirmLabel,  type, severity} = props;
        if (!cancelLabel) cancelLabel = i18n.t("common.no");
        if (!confirmLabel) confirmLabel = i18n.t("common.yes");
        if (!type) type = "info";
        if (!severity) severity = "info";
        return this.showDialog({
            title,
            message,
            cancelLabel,
            confirmLabel,
            type,
            severity,
        });
    }
    showDialog({ ...props }: ConfirmDialogProps): Promise<boolean> {
        return new Promise((resolve) => {
            const containerElement = document.createElement("div");
            document.body.appendChild(containerElement);
            const root = createRoot(containerElement);

            const handleClose = (result: boolean) => {
                root.unmount();
                containerElement.remove();
                resolve(result);
            };

            root.render(<ConfirmDialog {...props} onClose={handleClose} />);
        });
    }
    showInfoMessage(message: string, title = "Information"): Promise<void> {
        return this.showDialog({
            title,
            message,
            cancelLabel: "",
            confirmLabel: i18n.t("common.ok"),
            type: "info",
            severity: "info",
        }).then(() => { });
    }
    showErrorMessage(message: string, title = "Information"): Promise<void> {
        return this.showDialog({
            title,
            message,
            cancelLabel: "",
            confirmLabel: i18n.t("common.ok"),
            type: "error",
            severity: "error",
        }).then(() => { });
    }
    showInfoComponent(component: React.ReactNode, title = "Information", message: string): Promise<void> {
        return this.showDialog({
            title,
            message: message,
            component,
            cancelLabel: "",
            confirmLabel: i18n.t("common.ok"),
            type: "info",
            severity: "info",
        }).then(() => { });
    }
    confirmDiscardChanges(): Promise<boolean> {
        return this.showDialog({
            title: i18n.t('common.back_button.unsaved_changes_title'),
            message: i18n.t('common.back_button.unsaved_changes_message'),
            cancelLabel: i18n.t('common.buttons.cancel'),
            confirmLabel: i18n.t('common.buttons.yes'),
            severity: "warning",
        })
    }
}
export const dialogService = new DialogService();
