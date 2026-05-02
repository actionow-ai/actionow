import { CanvasEditorPage } from "@/components/canvas/canvas-editor-page";

interface CanvasEditorRouteProps {
  params: Promise<{ canvasId: string }>;
}

export default async function CanvasEditorRoute({ params }: CanvasEditorRouteProps) {
  const { canvasId } = await params;
  return <CanvasEditorPage canvasId={canvasId} />;
}
